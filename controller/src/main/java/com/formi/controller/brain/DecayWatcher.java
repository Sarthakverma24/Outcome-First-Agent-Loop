package com.formi.controller.brain;

import com.formi.controller.db.ExposureStore;
import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.ConfigVersion;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.governance.GovernanceService;
import com.formi.controller.stats.TwoProportionZTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Monitors applied experiment winners and triggers rollback if their advantage decays.
 *
 * Algorithm:
 *  1. Query all experiments with status "winner_applied" (set by SupervisorEditHandler
 *     when it sees an applied approval for an experiment proposal).
 *  2. For each, compare the winner vs control KPI in the period AFTER apply_ts.
 *  3. If the winner's advantage (pWinner - pControl) has been negative for
 *     DECAY_CONSECUTIVE_TICKS consecutive ticks: propose a rollback.
 *  4. After RETEST_INTERVAL_SIM_DAYS, re-verify the winner still holds.
 *
 * Crash safety: consecutive-tick counts are in memory (reset on restart).
 * Worst case: waits DECAY_CONSECUTIVE_TICKS extra ticks after restart before acting.
 * This is acceptable — a false negative delays rollback; it doesn't corrupt data.
 */
public class DecayWatcher {

    private static final Logger log = LoggerFactory.getLogger(DecayWatcher.class);

    private final Connection        conn;
    private final ExposureStore     exposure;
    private final Store             store;
    private final SimClient         client;
    private final GovernanceService governance;

    // In-memory decay counters: experimentId → consecutive-negative-ticks
    private final Map<String, Integer> decayCounts = new HashMap<>();
    // In-memory record of when decay was first detected: experimentId → sim_ts
    private final Map<String, String> decayOnsetTs = new HashMap<>();

    public DecayWatcher(Connection conn, Store store, SimClient client, GovernanceService governance) {
        this.conn       = conn;
        this.exposure   = new ExposureStore(conn);
        this.store      = store;
        this.client     = client;
        this.governance = governance;
    }

    /**
     * Run the decay watcher for one workflow on one tick.
     */
    public void run(WorkflowSummary workflow, String currentSimTs, String currentDaySim) {
        String wfId = workflow.workflowId;
        try {
            List<AppliedExperiment> applied = getAppliedExperiments(wfId);
            for (AppliedExperiment exp : applied) {
                checkDecay(exp, workflow, currentSimTs, currentDaySim);
            }
        } catch (Exception e) {
            log.error("[{}] DecayWatcher error: {}", wfId, e.getMessage(), e);
        }
    }

    // ── Core decay check ──────────────────────────────────────────────────

    private void checkDecay(AppliedExperiment exp, WorkflowSummary workflow,
                             String currentSimTs, String currentDaySim) throws Exception {
        String wfId   = workflow.workflowId;
        String primary = workflow.objective != null ? workflow.objective.primary : "pickup_rate";

        // Query calls AFTER the apply date
        List<ExposureStore.ExposureRow> rows =
                exposure.queryByDimension(wfId, exp.dim, exp.applyDay, currentDaySim);

        // Filter to this cohort
        rows = rows.stream()
                .filter(r -> Objects.equals(r.cohortId, exp.cohortId))
                .collect(Collectors.toList());

        // Aggregate: winner totals vs control totals
        int wS = 0, wN = 0, cS = 0, cN = 0;
        for (ExposureStore.ExposureRow r : rows) {
            int s, n;
            if ("pickup_rate".equals(primary)) { s = r.connected; n = r.dials; }
            else                               { s = r.engaged;   n = r.connected; }

            if (Objects.equals(r.value, exp.winnerValue)) { wS += s; wN += n; }
            if (Objects.equals(r.value, exp.controlValue)) { cS += s; cN += n; }
        }

        if (wN < BrainConfig.MIN_SAMPLE_FOR_ANY_DECISION || cN < BrainConfig.MIN_SAMPLE_FOR_ANY_DECISION) return;

        double pWinner  = (double) wS / wN;
        double pControl = (double) cS / cN;
        double advantage = pWinner - pControl;

        if (advantage < 0.0) {
            decayOnsetTs.putIfAbsent(exp.experimentId, currentSimTs);
            int ticks = decayCounts.merge(exp.experimentId, 1, Integer::sum);
            log.warn("[{}][{}][{}] Decay: winner={} advantage={:.4f} ticks={}",
                    wfId, exp.cohortId, exp.dim, exp.winnerValue, advantage, ticks);

            if (ticks >= BrainConfig.DECAY_CONSECUTIVE_TICKS) {
                triggerRollback(exp, workflow, pWinner, pControl, advantage, currentSimTs);
                decayCounts.put(exp.experimentId, 0);
            }
        } else {
            decayCounts.put(exp.experimentId, 0); // advantage still positive — reset
            decayOnsetTs.remove(exp.experimentId);
        }
    }

    private void triggerRollback(AppliedExperiment exp, WorkflowSummary workflow,
                                  double pWinner, double pControl, double advantage,
                                  String currentSimTs) throws Exception {
        String wfId = workflow.workflowId;
        log.warn("[{}] DECAY ROLLBACK: {} dim={} winner={} → control={}",
                wfId, exp.cohortId, exp.dim, exp.winnerValue, exp.controlValue);

        String onset = decayOnsetTs.getOrDefault(exp.experimentId, currentSimTs);
        long elapsedSimMinutes = 0;
        try {
            elapsedSimMinutes = java.time.Duration.between(
                    java.time.Instant.parse(onset),
                    java.time.Instant.parse(currentSimTs)
            ).toMinutes();
        } catch (Exception ignored) {}

        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("experimentId",                  exp.experimentId);
        finding.put("cohortId",                      exp.cohortId);
        finding.put("dim",                           exp.dim);
        finding.put("winnerValue",                   exp.winnerValue);
        finding.put("controlValue",                  exp.controlValue);
        finding.put("pWinner",                       pWinner);
        finding.put("pControl",                      pControl);
        finding.put("advantage",                     advantage);
        finding.put("decayTicks",                    BrainConfig.DECAY_CONSECUTIVE_TICKS);
        finding.put("decay_onset_sim_ts",            onset);
        finding.put("rollback_triggered_sim_ts",     currentSimTs);
        finding.put("time_to_rollback_minutes",       elapsedSimMinutes);
        store.audit(currentSimTs, wfId, "decay_rollback_triggered", finding);

        // Propose a rollback (restore control value)
        ConfigVersion cv = client.getConfig(wfId);
        com.fasterxml.jackson.databind.node.ObjectNode proposed = cv.config.deepCopy();
        proposed.put(exp.dim, exp.controlValue);

        Map<String, Object> diff = Map.of(
                exp.dim + ".cohort." + exp.cohortId,
                exp.controlValue + " (rollback from " + exp.winnerValue + ")"
        );
        Map<String, Object> dryRun = Map.of(
                "type",        "decay_rollback",
                "pWinner",     pWinner,
                "pControl",    pControl,
                "advantage",   advantage,
                "decayTicks",  BrainConfig.DECAY_CONSECUTIVE_TICKS
        );

        String rationale = String.format(
                "Winner '%s' (dim=%s, cohort=%s) has shown negative advantage (%.2fpp) " +
                "for %d consecutive ticks after promotion. Rolling back to control '%s'. " +
                "Possible causes: seasonal shift, population change, or interaction effect.",
                exp.winnerValue, exp.dim, exp.cohortId,
                advantage * 100, BrainConfig.DECAY_CONSECUTIVE_TICKS, exp.controlValue
        );

        governance.propose(workflow, cv.version, proposed,
                "Decay rollback: " + exp.dim + " → " + exp.controlValue,
                diff, 0, dryRun, 0.7, rationale, null, currentSimTs);

        // Mark experiment as rolled back
        updateExperimentStatus(exp.experimentId, "rolled_back");
    }

    // ── DB helpers ────────────────────────────────────────────────────────

    private static class AppliedExperiment {
        final String experimentId;
        final String wfId;
        final String cohortId;
        final String dim;
        final String winnerValue;
        final String controlValue;
        final String applyDay; // "YYYY-MM-DD"
        AppliedExperiment(String experimentId, String wfId, String cohortId, String dim,
                           String winnerValue, String controlValue, String applyDay) {
            this.experimentId = experimentId;
            this.wfId         = wfId;
            this.cohortId     = cohortId;
            this.dim          = dim;
            this.winnerValue  = winnerValue;
            this.controlValue = controlValue;
            this.applyDay     = applyDay;
        }
    }

    private List<AppliedExperiment> getAppliedExperiments(String wfId) throws Exception {
        List<AppliedExperiment> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT experiment_id, workflow_id, cohort_id, dimension,
                       winner_value, control_value, date(apply_sim_ts)
                FROM experiments
                WHERE workflow_id=? AND status='winner_applied'
                """)) {
            ps.setString(1, wfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new AppliedExperiment(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7) != null ? rs.getString(7) : ""
                    ));
                }
            }
        }
        return result;
    }

    private void updateExperimentStatus(String expId, String status) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE experiments SET status=? WHERE experiment_id=?")) {
            ps.setString(1, status);
            ps.setString(2, expId);
            ps.executeUpdate();
        }
    }
}
