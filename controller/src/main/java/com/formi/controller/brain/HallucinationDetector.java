package com.formi.controller.brain;

import com.formi.controller.db.ExposureStore;
import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.ConfigVersion;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.governance.GovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.*;

/**
 * Detects sustained spikes in the qa.hallucination_flag rate per prompt_version.
 *
 * Algorithm:
 *  1. Query hallucination rate for each prompt_version over the last BASELINE_WINDOW_DAYS.
 *  2. Compute the workflow baseline = mean hallucination rate across all prompt_versions
 *     weighted by connected calls.
 *  3. Flag a prompt_version when:
 *     - It has ≥ MIN_CONNECTED_FOR_HALLUCINATION_CHECK connected calls, AND
 *     - Its rate > baseline × HALLUCINATION_RATE_MULTIPLIER, AND
 *     - This has been true for ≥ CONSECUTIVE_HALLU_TICKS consecutive ticks.
 *  4. Response:
 *     - Find the last prompt_version with a healthy rate from config_versions history.
 *     - If that version has a validation_run_id in its config JSON, propose a rollback.
 *     - If not, write an "awaiting_validation" audit entry and do NOT propose.
 *       (Hard rule: never auto-apply a prompt change. It always needs an approval.)
 *
 * Note: We NEVER auto-apply a prompt change regardless of the auto_apply policy.
 * Prompt changes affect what the AI says on calls and require human review.
 */
public class HallucinationDetector {

    private static final Logger log = LoggerFactory.getLogger(HallucinationDetector.class);

    private final ExposureStore     exposure;
    private final Store             store;
    private final SimClient         client;
    private final GovernanceService governance;
    private final Connection        conn;

    // In-memory consecutive-tick counters: workflowId → promptVersion → count
    private final Map<String, Map<String, Integer>> consecutiveCounts = new HashMap<>();

    public HallucinationDetector(Connection conn, Store store, SimClient client, GovernanceService governance) {
        this.conn       = conn;
        this.exposure   = new ExposureStore(conn);
        this.store      = store;
        this.client     = client;
        this.governance = governance;
    }

    /**
     * Run the hallucination detector for one workflow on one tick.
     */
    public void run(WorkflowSummary workflow, String currentDaySim, String currentSimTs) {
        String wfId = workflow.workflowId;
        try {
            // Query over the full baseline window
            String fromDay = shiftDate(currentDaySim, -BrainConfig.HALLUCINATION_BASELINE_WINDOW_DAYS);
            List<ExposureStore.HalluRow> rows =
                    exposure.queryHallucinationByPrompt(wfId, fromDay, currentDaySim);

            if (rows.isEmpty()) return;

            // Compute weighted baseline (all prompt versions combined)
            long totalConnected    = rows.stream().mapToLong(r -> r.connected).sum();
            long totalHallu        = rows.stream().mapToLong(r -> r.halluCount).sum();
            if (totalConnected == 0) return;
            double baseline = (double) totalHallu / totalConnected;

            Map<String, Integer> wfCounts = consecutiveCounts.computeIfAbsent(wfId, k -> new HashMap<>());

            for (ExposureStore.HalluRow row : rows) {
                if (row.connected < BrainConfig.MIN_CONNECTED_FOR_HALLUCINATION_CHECK) {
                    wfCounts.put(row.promptVersion, 0);
                    continue;
                }
                double rate = row.halluRate();
                boolean elevated = baseline > 0 && rate > baseline * BrainConfig.HALLUCINATION_RATE_MULTIPLIER;

                if (elevated) {
                    int ticks = wfCounts.merge(row.promptVersion, 1, Integer::sum);
                    log.info("[{}] prompt={} hallu_rate={:.4f} baseline={:.4f} ticks={}",
                            wfId, row.promptVersion, rate, baseline, ticks);

                    if (ticks >= BrainConfig.CONSECUTIVE_HALLU_TICKS) {
                        flag(workflow, row, rate, baseline, currentSimTs, currentDaySim);
                        wfCounts.put(row.promptVersion, 0);
                    }
                } else {
                    wfCounts.put(row.promptVersion, 0);
                }
            }
        } catch (Exception e) {
            log.error("[{}] HallucinationDetector error: {}", wfId, e.getMessage(), e);
        }
    }

    // ── Act on a flagged prompt version ───────────────────────────────────

    private void flag(WorkflowSummary workflow, ExposureStore.HalluRow bad,
                      double rate, double baseline, String currentSimTs, String currentDaySim) throws Exception {
        String wfId = workflow.workflowId;
        log.warn("[{}] HALLUCINATION SPIKE: prompt={} rate={:.4f} vs baseline={:.4f}",
                wfId, bad.promptVersion, rate, baseline);

        // Audit the finding
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("promptVersion",   bad.promptVersion);
        finding.put("connected",       bad.connected);
        finding.put("halluCount",      bad.halluCount);
        finding.put("halluRate",       rate);
        finding.put("baseline",        baseline);
        finding.put("multiplier",      rate / (baseline > 0 ? baseline : 1));
        finding.put("threshold",       BrainConfig.HALLUCINATION_RATE_MULTIPLIER);
        store.audit(currentSimTs, wfId, "hallucination_spike", finding);

        // Find the last healthy prompt version
        String healthyVersion = findLastHealthyPrompt(wfId, bad.promptVersion, baseline);
        if (healthyVersion == null) {
            log.warn("[{}] No healthy prompt version found — cannot propose rollback.", wfId);
            Map<String, Object> awaiting = new LinkedHashMap<>();
            awaiting.put("reason",         "no_healthy_prompt_found");
            awaiting.put("badPrompt",       bad.promptVersion);
            store.audit(currentSimTs, wfId, "awaiting_validation", awaiting);
            return;
        }

        // Find validation_run_id for the healthy version from config history
        String validationRunId = findValidationRunId(wfId, healthyVersion);
        if (validationRunId == null) {
            log.warn("[{}] No validation_run_id for prompt {} — awaiting validation.", wfId, healthyVersion);
            Map<String, Object> awaiting = new LinkedHashMap<>();
            awaiting.put("reason",          "no_validation_run_id");
            awaiting.put("targetPrompt",    healthyVersion);
            awaiting.put("badPrompt",       bad.promptVersion);
            store.audit(currentSimTs, wfId, "awaiting_validation", awaiting);
            return;
        }

        // We have everything needed — propose a rollback
        proposeRollback(workflow, bad.promptVersion, healthyVersion, validationRunId,
                rate, baseline, bad.connected, bad.halluCount, currentSimTs);
    }

    /** Propose a prompt rollback via the approval system (never auto-apply). */
    private void proposeRollback(WorkflowSummary workflow, String badPrompt, String targetPrompt,
                                  String validationRunId, double rate, double baseline,
                                  int connected, int halluCount, String currentSimTs) throws Exception {
        String wfId = workflow.workflowId;
        ConfigVersion cv = client.getConfig(wfId);

        com.fasterxml.jackson.databind.node.ObjectNode proposed = cv.config.deepCopy();
        // Set the prompt_version field in the config to the healthy version
        proposed.put("prompt_version", targetPrompt);

        Map<String, Object> diff = Map.of(
                "prompt_version", targetPrompt + " (rollback from " + badPrompt + ")"
        );
        Map<String, Object> evidence = Map.of(
                "validation_run_id", validationRunId,
                "badPromptVersion",  badPrompt,
                "halluRate",         rate,
                "baseline",          baseline,
                "connectedCount",    connected,
                "halluCount",        halluCount
        );
        Map<String, Object> dryRun = new LinkedHashMap<>();
        dryRun.put("type",           "rollback");
        dryRun.put("fromPrompt",     badPrompt);
        dryRun.put("toPrompt",       targetPrompt);
        dryRun.put("validationRunId", validationRunId);
        dryRun.put("expectedHalluRateReduction",
                String.format("%.1f%%", (rate - baseline) * 100));

        String rationale = String.format(
                "Prompt version %s has a hallucination rate of %.2f%% vs baseline %.2f%% " +
                "(%.1fx the threshold of %.1fx). Sustained for %d consecutive ticks. " +
                "Proposing rollback to %s which has validation_run_id=%s.",
                badPrompt, rate * 100, baseline * 100,
                rate / (baseline > 0 ? baseline : 1),
                BrainConfig.HALLUCINATION_RATE_MULTIPLIER,
                BrainConfig.CONSECUTIVE_HALLU_TICKS,
                targetPrompt, validationRunId
        );

        governance.propose(workflow, cv.version, proposed,
                "Hallucination rollback: " + badPrompt + " → " + targetPrompt,
                diff, 0, dryRun, 0.8, rationale, evidence, currentSimTs);
        log.info("[{}] Hallucination rollback proposal submitted: {} → {}", wfId, badPrompt, targetPrompt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Find the most recent prompt_version in config_versions that is NOT the bad one
     * and had a lower hallucination rate. We use the config_versions table (populated
     * by the ingester on every config_changed event) to find previous versions.
     */
    private String findLastHealthyPrompt(String workflowId, String badPrompt, double baseline) {
        try (var ps = conn.prepareStatement("""
                SELECT config_json, ts FROM config_versions
                WHERE workflow_id = ?
                  AND ts < (SELECT MAX(ts) FROM config_versions
                            WHERE workflow_id = ? AND config_json LIKE '%' || ? || '%')
                ORDER BY ts DESC
                LIMIT 20
                """)) {
            ps.setString(1, workflowId);
            ps.setString(2, workflowId);
            ps.setString(3, badPrompt);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString("config_json");
                    var node = mapper.readTree(json);
                    var pv = node.path("prompt_version");
                    if (!pv.isMissingNode() && !pv.asText().equals(badPrompt)) {
                        return pv.asText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[{}] findLastHealthyPrompt error: {}", workflowId, e.getMessage());
        }
        return null;
    }

    /**
     * Look for a validation_run_id in the config JSON for the given prompt_version.
     * The simulator stores it inside the config under "validation_run_id" or similar.
     */
    private String findValidationRunId(String workflowId, String promptVersion) {
        try (var ps = conn.prepareStatement("""
                SELECT config_json FROM config_versions
                WHERE workflow_id = ? AND config_json LIKE '%' || ? || '%'
                ORDER BY ts DESC LIMIT 1
                """)) {
            ps.setString(1, workflowId);
            ps.setString(2, promptVersion);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    var node = mapper.readTree(rs.getString("config_json"));
                    var vrid = node.path("validation_run_id");
                    if (!vrid.isMissingNode() && !vrid.asText().isBlank()) {
                        return vrid.asText();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[{}] findValidationRunId error: {}", workflowId, e.getMessage());
        }
        return null;
    }

    /** Shift a "YYYY-MM-DD" date string by {@code days} (negative = backwards). */
    private static String shiftDate(String daySim, int days) {
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(daySim);
            return d.plusDays(days).toString();
        } catch (Exception e) {
            return daySim; // fail safe — do not crash the loop
        }
    }
}
