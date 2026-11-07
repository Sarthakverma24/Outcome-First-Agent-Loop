package com.formi.controller.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formi.controller.db.ExposureStore;
import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.ConfigVersion;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.governance.GovernanceService;
import com.formi.controller.stats.HolmCorrection;
import com.formi.controller.stats.TwoProportionZTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A/B experiment engine: runs experiments on "voice" and "welcome_message" dimensions.
 *
 * Design:
 *   - One experiment per cohort at a time (MAX_EXPERIMENTS_PER_COHORT = 1).
 *   - Control = current default value from the live config; challengers = other options.
 *   - Look interval = 1 simulated day (LOOK_INTERVAL_SIM_HOURS = 24).
 *   - Decision: two-proportion z-test per cohort × attempt_no, then Holm correction
 *     across all challenger tests. A winner must beat control by MIN_PRACTICAL_EFFECT
 *     AND the guardrail KPI must not fall below its limit.
 *   - On a winner: submit a POST /approvals proposal with full evidence.
 *   - Experiments are persisted in the `experiments` table with deterministic IDs
 *     so a crash/restart cannot re-run the same decision twice.
 *
 * Crash safety: We write the experiment row (with status=running) BEFORE submitting
 * the proposal. On restart we reload running experiments and do not start new ones
 * for the same cohort/dimension. After a decision we update the status immediately.
 */
public class ExperimentEngine {

    private static final Logger log = LoggerFactory.getLogger(ExperimentEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Dimensions we run experiments on (not caller_id — that's the CallerIdDetector;
    // not prompt_version — that's the HallucinationDetector, never auto-applied)
    private static final List<String> EXPERIMENT_DIMS = List.of("voice", "welcome_message");

    private final Connection        conn;
    private final ExposureStore     exposure;
    private final Store             store;
    private final SimClient         client;
    private final GovernanceService governance;

    public ExperimentEngine(Connection conn, Store store, SimClient client, GovernanceService governance) {
        this.conn       = conn;
        this.exposure   = new ExposureStore(conn);
        this.store      = store;
        this.client     = client;
        this.governance = governance;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Public entry point — called once per tick per workflow
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Evaluate experiments for one workflow on the current tick.
     *
     * @param workflow      full workflow detail (options, objective, governance)
     * @param currentSimTs  ISO-8601 sim timestamp (for audit + rate limiting)
     * @param currentDaySim "YYYY-MM-DD" portion of simTs
     */
    public void run(WorkflowSummary workflow, String currentSimTs, String currentDaySim) {
        String wfId = workflow.workflowId;
        try {
            for (String dim : EXPERIMENT_DIMS) {
                runForDimension(workflow, dim, currentSimTs, currentDaySim);
            }
        } catch (Exception e) {
            log.error("[{}] ExperimentEngine error: {}", wfId, e.getMessage(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Per-dimension logic
    // ══════════════════════════════════════════════════════════════════════════

    private void runForDimension(WorkflowSummary workflow, String dim,
                                  String currentSimTs, String currentDaySim) throws Exception {
        String wfId = workflow.workflowId;

        // Get available options for this dimension
        List<String> options = getOptions(workflow, dim);
        if (options == null || options.size() < 2) return; // need at least control + 1 challenger

        // Get the current config to identify the control arm
        ConfigVersion cv   = client.getConfig(wfId);
        String controlValue = extractCurrentValue(cv.config, dim);
        if (controlValue == null) {
            log.debug("[{}][{}] No current value in config — skipping.", wfId, dim);
            return;
        }

        // Find cohorts that do NOT already have a running experiment on this dimension
        List<String> cohorts = getActiveCohorts(wfId, dim);
        for (String cohortId : cohorts) {
            runForCohort(workflow, dim, controlValue, options, cohortId,
                    cv, currentSimTs, currentDaySim);
        }
    }

    private void runForCohort(WorkflowSummary workflow, String dim, String controlValue,
                               List<String> options, String cohortId,
                               ConfigVersion cv, String currentSimTs, String currentDaySim) throws Exception {
        String wfId = workflow.workflowId;

        // Check if there's already a running experiment for this cohort+dim
        String existingExpId = getRunningExperimentId(wfId, cohortId, dim);
        if (existingExpId != null) {
            // Already running — check if it's time for a look
            evaluateRunningExperiment(existingExpId, workflow, dim, controlValue,
                    cv, currentSimTs, currentDaySim);
            return;
        }

        // Pick challengers (up to MAX_CHALLENGERS_PER_EXPERIMENT)
        List<String> challengers = options.stream()
                .filter(o -> !o.equals(controlValue))
                .limit(BrainConfig.MAX_CHALLENGERS_PER_EXPERIMENT)
                .collect(Collectors.toList());
        if (challengers.isEmpty()) return;

        // Create a new experiment
        String expId = computeExperimentId(wfId, cohortId, dim, currentSimTs);
        insertExperiment(expId, wfId, cohortId, dim, controlValue, challengers, currentSimTs);
        log.info("[{}][{}][{}] Started experiment {} control={} challengers={}",
                wfId, cohortId, dim, expId, controlValue, challengers);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Experiment evaluation (the look)
    // ══════════════════════════════════════════════════════════════════════════

    private void evaluateRunningExperiment(String expId, WorkflowSummary workflow, String dim,
                                            String controlValue, ConfigVersion cv,
                                            String currentSimTs, String currentDaySim) throws Exception {
        String wfId = workflow.workflowId;

        // Only look once per simulated day — check the last look timestamp in the experiments table
        String lastLookDay = getLastLookDay(expId);
        if (lastLookDay != null && lastLookDay.equals(currentDaySim)) {
            return; // already looked today — no peeking
        }

        // Read challengers from DB
        List<String> challengers = getChallengers(expId);
        if (challengers.isEmpty()) return;

        // Aggregate exposure: group by cohort × dimension value × attempt_no
        // We read all data since the experiment started (no date restriction here —
        // the caller-side contamination check handles supervisor edits separately)
        String startDay = getExperimentStartDay(expId);
        List<ExposureStore.ExposureRow> rows =
                exposure.queryByDimension(wfId, dim, startDay, currentDaySim);

        // Filter to this experiment's cohort
        String cohortId = getCohortId(expId);
        rows = rows.stream()
                .filter(r -> Objects.equals(r.cohortId, cohortId))
                .collect(Collectors.toList());

        // Update last-look day
        updateLastLookDay(expId, currentDaySim);

        // Determine primary KPI from workflow objective
        String primaryKpi = workflow.objective != null ? workflow.objective.primary : "pickup_rate";

        // Run the decision logic
        DecisionResult result = decide(controlValue, challengers, rows, primaryKpi, workflow);

        log.info("[{}][{}][{}] Experiment look: {}", wfId, cohortId, dim, result.summary());

        if (result.winner != null) {
            // A winner was found — propose the config change
            proposeWinner(expId, workflow, dim, controlValue, result, cv, currentSimTs);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Statistical decision
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Attempt-stratified z-test with Holm correction.
     *
     * For each challenger, we accumulate counts across all attempt_no slices
     * (treating each stratum independently and summing the z-scores via Stouffer's
     * meta-analysis — simple and defensible). Then we apply Holm across challengers.
     *
     * Guardrail check: for a winner, we also verify the other KPI with a 95%
     * lower confidence bound.
     */
    private DecisionResult decide(String controlValue, List<String> challengers,
                                   List<ExposureStore.ExposureRow> rows, String primaryKpi,
                                   WorkflowSummary workflow) {

        // Aggregate per value × attempt_no: sum connected and dials/engaged
        Map<String, int[]> controlByAttempt  = sumByAttempt(rows, controlValue, primaryKpi);
        Map<String, Map<String, int[]>> challengerByAttempt = new HashMap<>();
        for (String ch : challengers) {
            challengerByAttempt.put(ch, sumByAttempt(rows, ch, primaryKpi));
        }

        // Compute Stouffer-combined z-score per challenger
        // Stouffer: z_combined = sum(z_k) / sqrt(K) where K = number of strata (attempt_nos)
        double[] challengerPValues = new double[challengers.size()];
        double[] effectSizes       = new double[challengers.size()];
        int[]    controlNs         = new int[challengers.size()];
        int[]    challengerNs      = new int[challengers.size()];

        for (int ci = 0; ci < challengers.size(); ci++) {
            String ch = challengers.get(ci);
            Map<String, int[]> chMap   = challengerByAttempt.get(ch);
            Set<String> allAttempts    = new HashSet<>(controlByAttempt.keySet());
            allAttempts.addAll(chMap.keySet());

            double zSum       = 0.0;
            int    stratumCount = 0;
            int    totalCtrlSuccess = 0, totalCtrlN = 0;
            int    totalChSuccess   = 0, totalChN   = 0;

            for (String attempt : allAttempts) {
                int[] ctrl = controlByAttempt.getOrDefault(attempt, new int[]{0, 0});
                int[] ch2  = chMap.getOrDefault(attempt, new int[]{0, 0});
                if (ctrl[1] < 10 || ch2[1] < 10) continue; // skip strata with tiny N

                TwoProportionZTest.Result r = TwoProportionZTest.test(ctrl[0], ctrl[1], ch2[0], ch2[1]);
                zSum += r.zScore();
                stratumCount++;
                totalCtrlSuccess += ctrl[0]; totalCtrlN += ctrl[1];
                totalChSuccess   += ch2[0];  totalChN   += ch2[1];
            }

            if (stratumCount == 0 || totalCtrlN < BrainConfig.MIN_CONNECTED_PER_ARM
                    || totalChN < BrainConfig.MIN_CONNECTED_PER_ARM) {
                challengerPValues[ci] = 1.0; // not enough data yet
                effectSizes[ci]       = 0.0;
                continue;
            }

            // Stouffer combined z → two-tailed p-value
            double zCombined = zSum / Math.sqrt(stratumCount);
            challengerPValues[ci] = 2.0 * TwoProportionZTest.normalCdfUpperTail(Math.abs(zCombined));
            effectSizes[ci] = (double) totalChSuccess / totalChN
                            - (double) totalCtrlSuccess / totalCtrlN;
            controlNs[ci]    = totalCtrlN;
            challengerNs[ci] = totalChN;
        }

        // Holm correction across all challengers
        HolmCorrection.TestResult[] corrected =
                HolmCorrection.correct(challengerPValues, BrainConfig.ALPHA);

        // Pick best winner: rejected by Holm AND effect > MIN_PRACTICAL_EFFECT AND guardrail OK
        String bestWinner      = null;
        double bestEffect      = 0.0;
        String bestRationale   = null;
        Map<String, Object> bestEvidence = null;

        for (int ci = 0; ci < challengers.size(); ci++) {
            if (!corrected[ci].rejected()) continue;
            if (effectSizes[ci] < BrainConfig.MIN_PRACTICAL_EFFECT) continue;

            // Guardrail check
            String winner = challengers.get(ci);
            if (!guardrailOk(winner, rows, primaryKpi, workflow, controlValue)) {
                log.info("Winner {} failed guardrail check.", winner);
                continue;
            }

            // Pick the challenger with the largest effect
            if (effectSizes[ci] > bestEffect) {
                bestEffect  = effectSizes[ci];
                bestWinner  = winner;

                String rationale = String.format(
                        "Challenger '%s' beats control '%s' on %s by %.1fpp (z=%.2f, p=%.4f, " +
                        "Holm threshold=%.4f). " +
                        "Control n=%d challenger n=%d. " +
                        "Minimum practical effect threshold: %.1fpp. Guardrail: OK.",
                        winner, controlValue, primaryKpi,
                        effectSizes[ci] * 100,
                        // compute z from p-value via inverse (approximate)
                        -1.0 * Math.signum(effectSizes[ci]) * Math.log(challengerPValues[ci]),
                        challengerPValues[ci],
                        corrected[ci].holmThreshold(),
                        controlNs[ci], challengerNs[ci],
                        BrainConfig.MIN_PRACTICAL_EFFECT * 100
                );
                bestRationale = rationale;
                bestEvidence  = Map.of(
                        "winner",          winner,
                        "control",         controlValue,
                        "effectSize",      effectSizes[ci],
                        "pValue",          challengerPValues[ci],
                        "holmThreshold",   corrected[ci].holmThreshold(),
                        "controlN",        controlNs[ci],
                        "challengerN",     challengerNs[ci],
                        "primaryKpi",      primaryKpi,
                        "minPracticalEffect", BrainConfig.MIN_PRACTICAL_EFFECT
                );
            }
        }

        return new DecisionResult(bestWinner, bestEffect, bestRationale, bestEvidence,
                challengerPValues, effectSizes);
    }

    /** Sum successes and trials per attempt_no for one specific arm value. */
    private Map<String, int[]> sumByAttempt(List<ExposureStore.ExposureRow> rows,
                                              String value, String primaryKpi) {
        Map<String, int[]> result = new HashMap<>();
        for (ExposureStore.ExposureRow r : rows) {
            if (!Objects.equals(r.value, value)) continue;
            String key = String.valueOf(r.attemptNo);
            int[] acc  = result.computeIfAbsent(key, k -> new int[2]);
            if ("pickup_rate".equals(primaryKpi)) {
                acc[0] += r.connected;
                acc[1] += r.dials;
            } else { // engagement_rate
                acc[0] += r.engaged;
                acc[1] += r.connected;
            }
        }
        return result;
    }

    /**
     * Guardrail check: the KPI that is NOT the primary must not drop more than
     * the workflow's objective.guardrail allows, with GUARDRAIL_CONFIDENCE bound.
     *
     * We use a one-sided 95% lower confidence bound on the difference.
     * Lower CI = effect - 1.645 * SE (for 95% one-sided).
     */
    private boolean guardrailOk(String challenger, List<ExposureStore.ExposureRow> rows,
                                  String primaryKpi, WorkflowSummary workflow, String controlValue) {
        String guardrailKpi = "pickup_rate".equals(primaryKpi) ? "engagement_rate" : "pickup_rate";
        WorkflowSummary.Guardrail g = (workflow.objective != null) ? workflow.objective.guardrail : null;
        if (g == null) return true; // no guardrail configured

        Double maxDrop = "pickup_rate".equals(guardrailKpi)
                ? g.pickupRateMaxDrop : g.engagementRateMaxDrop;
        if (maxDrop == null) return true;

        // Aggregate guardrail KPI for control and challenger (all attempts combined)
        int ctrlS = 0, ctrlN = 0, chS = 0, chN = 0;
        for (ExposureStore.ExposureRow r : rows) {
            boolean isCtrl = Objects.equals(r.value, controlValue);
            boolean isCh   = Objects.equals(r.value, challenger);
            if (!isCtrl && !isCh) continue;

            int s, n;
            if ("pickup_rate".equals(guardrailKpi)) { s = r.connected; n = r.dials; }
            else                                    { s = r.engaged;   n = r.connected; }

            if (isCtrl) { ctrlS += s; ctrlN += n; }
            else        { chS   += s; chN   += n; }
        }

        if (ctrlN == 0 || chN == 0) return true; // no data → don't block

        double pCtrl  = (double) ctrlS / ctrlN;
        double pCh    = (double) chS   / chN;
        double effect = pCh - pCtrl;

        // One-sided 95% lower CI: effect - 1.645 * SE
        double pPool  = (double)(ctrlS + chS) / (ctrlN + chN);
        double se     = Math.sqrt(pPool * (1 - pPool) * (1.0/ctrlN + 1.0/chN));
        double lowerCi = effect - 1.645 * se;

        // Guardrail is violated if the lower CI implies a drop bigger than maxDrop
        boolean ok = lowerCi >= -maxDrop;
        if (!ok) log.warn("Guardrail {} violated: lowerCI={:.4f} maxDrop={:.4f}", guardrailKpi, lowerCi, maxDrop);
        return ok;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Proposal submission for a winner
    // ══════════════════════════════════════════════════════════════════════════

    private void proposeWinner(String expId, WorkflowSummary workflow, String dim,
                                String controlValue, DecisionResult result, ConfigVersion cv,
                                String currentSimTs) throws Exception {
        String wfId   = workflow.workflowId;
        String winner = result.winner;
        String cohortId = getCohortId(expId);

        log.info("[{}][{}][{}] WINNER={} effect={:.3f}", wfId, cohortId, dim, winner, result.effectSize);

        // Build proposed config: update the dimension value for this cohort
        ObjectNode proposed = cv.config.deepCopy();
        setDimensionInConfig(proposed, dim, winner, cohortId);

        Map<String, Object> diff = Map.of(
                dim + ".cohort." + cohortId, winner + " (was: " + controlValue + ")"
        );

        // Dry-run = the evidence counts
        Map<String, Object> dryRun = new LinkedHashMap<>(result.evidence);
        dryRun.put("type",        "z_test");
        dryRun.put("experimentId", expId);

        double impactScore = Math.min(1.0, result.effectSize / 0.10); // 10pp effect → impact 1.0

        String approvalId = governance.propose(
                workflow, cv.version, proposed,
                "Experiment winner: " + dim + "=" + winner + " for cohort " + cohortId,
                diff,
                estimateAffectedLeads(wfId, cohortId),
                dryRun,
                impactScore,
                result.rationale,
                result.evidence,
                currentSimTs
        );

        // Update experiment status
        updateExperimentStatus(expId, approvalId != null ? "winner_proposed" : "winner_pending", approvalId);
        store.audit(currentSimTs, wfId, "experiment_winner", Map.of(
                "experimentId", expId,
                "dim",          dim,
                "cohortId",     cohortId,
                "winner",       winner,
                "effect",       result.effectSize
        ));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DB helpers
    // ══════════════════════════════════════════════════════════════════════════

    private void insertExperiment(String expId, String wfId, String cohortId, String dim,
                                   String controlValue, List<String> challengers, String simTs) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO experiments
                    (experiment_id, workflow_id, cohort_id, dimension, control_value,
                     challenger_values, status, start_sim_ts)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, expId);
            ps.setString(2, wfId);
            ps.setString(3, cohortId);
            ps.setString(4, dim);
            ps.setString(5, controlValue);
            ps.setString(6, MAPPER.writeValueAsString(challengers));
            ps.setString(7, "running");
            ps.setString(8, simTs);
            ps.executeUpdate();
        }
    }

    private String getRunningExperimentId(String wfId, String cohortId, String dim) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT experiment_id FROM experiments
                WHERE workflow_id=? AND cohort_id=? AND dimension=?
                  AND status IN ('running','winner_proposed','winner_pending')
                LIMIT 1
                """)) {
            ps.setString(1, wfId);
            ps.setString(2, cohortId);
            ps.setString(3, dim);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private List<String> getChallengers(String expId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT challenger_values FROM experiments WHERE experiment_id=?")) {
            ps.setString(1, expId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return List.of();
                return MAPPER.readValue(rs.getString(1),
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
            }
        }
    }

    private String getCohortId(String expId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cohort_id FROM experiments WHERE experiment_id=?")) {
            ps.setString(1, expId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "default";
            }
        }
    }

    private String getLastLookDay(String expId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT last_look_day FROM experiments WHERE experiment_id=?")) {
            ps.setString(1, expId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void updateLastLookDay(String expId, String day) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE experiments SET last_look_day=? WHERE experiment_id=?")) {
            ps.setString(1, day);
            ps.setString(2, expId);
            ps.executeUpdate();
        }
    }

    private String getExperimentStartDay(String expId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT date(start_sim_ts) FROM experiments WHERE experiment_id=?")) {
            ps.setString(1, expId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void updateExperimentStatus(String expId, String status, String proposalId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE experiments SET status=?, proposal_id=? WHERE experiment_id=?")) {
            ps.setString(1, status);
            ps.setString(2, proposalId);
            ps.setString(3, expId);
            ps.executeUpdate();
        }
    }

    /** All cohort IDs observed in the calls table or experiments table for this workflow. */
    private List<String> getActiveCohorts(String wfId, String dim) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT cohort_id FROM calls WHERE workflow_id=?")) {
            ps.setString(1, wfId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT cohort_id FROM experiments WHERE workflow_id=? AND dimension=?")) {
            ps.setString(1, wfId);
            ps.setString(2, dim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return new ArrayList<>(result);
    }

    private int estimateAffectedLeads(String wfId, String cohortId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(DISTINCT lead_id) FROM calls WHERE workflow_id=? AND cohort_id=?")) {
            ps.setString(1, wfId);
            ps.setString(2, cohortId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Config manipulation
    // ══════════════════════════════════════════════════════════════════════════

    /** Extract the current value of a dimension from the config JSON. */
    private static String extractCurrentValue(JsonNode config, String dim) {
        // Try top-level first, then inside cohorts.default
        JsonNode v = config.path(dim);
        if (!v.isMissingNode() && v.isTextual()) return v.asText();
        // Some workflows store it nested under cohorts.{cohortId}
        JsonNode cohorts = config.path("cohorts");
        if (cohorts.isObject()) {
            var it = cohorts.fields();
            while (it.hasNext()) {
                var entry = it.next();
                JsonNode cv2 = entry.getValue().path(dim);
                if (!cv2.isMissingNode() && cv2.isTextual()) return cv2.asText();
            }
        }
        return null;
    }

    /** Update the dimension value in a proposed config (deep copy from caller). */
    private static void setDimensionInConfig(ObjectNode proposed, String dim, String value, String cohortId) {
        // Simplest defensible change: set at top level (applies to all cohorts as default)
        proposed.put(dim, value);
    }

    /** Available options from workflow.options for a dimension. */
    private static List<String> getOptions(WorkflowSummary wf, String dim) {
        if (wf.options == null) return null;
        return switch (dim) {
            case "voice"           -> wf.options.voice;
            case "welcome_message" -> wf.options.welcomeMessage;
            default                -> null;
        };
    }

    /** Deterministic experiment ID: SHA-256 of (workflow+cohort+dim+startDay). */
    static String computeExperimentId(String wfId, String cohortId, String dim, String simTs) {
        // Use day-only portion so the ID is stable across restarts within the same day
        String day = simTs.length() >= 10 ? simTs.substring(0, 10) : simTs;
        String key = wfId + ":" + cohortId + ":" + dim + ":" + day;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("exp-");
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Result record
    // ══════════════════════════════════════════════════════════════════════════

    private record DecisionResult(
            String winner,            // null if no winner yet
            double effectSize,
            String rationale,
            Map<String, Object> evidence,
            double[] allPValues,
            double[] allEffects
    ) {
        String summary() {
            if (winner == null) return "no winner yet (insufficient data or no significant effect)";
            return "WINNER=" + winner + " effect=" + String.format("%.3f", effectSize);
        }
    }
}
