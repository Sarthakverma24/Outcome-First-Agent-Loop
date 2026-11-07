package com.formi.controller.brain;

import com.formi.controller.db.ExposureStore;
import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.governance.GovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.*;

/**
 * Detects caller-ID health problems and acts on them.
 *
 * Algorithm:
 *  1. Query the last CALLER_ID_WINDOW_DAYS of dials per caller_id for this workflow.
 *  2. Compute the mean pickup rate across all caller IDs combined (weighted by dials).
 *  3. Flag any caller_id whose pickup rate is more than MIN_PICKUP_GAP below the
 *     overall mean AND has at least MIN_DIALS_FOR_CALLER_ID_FLAG dials.
 *  4. Only act when the flag has been raised for CALLER_ID_CONSECUTIVE_FLAG_TICKS
 *     consecutive ticks (stored in memory per workflow+caller_id).
 *  5. If auto_apply allows "caller_id" and the required shift is ≤ max_shift:
 *     rotate via PUT /workflows/{id}/config (auto-apply path, no approval needed).
 *     Otherwise submit a POST /approvals proposal.
 *
 * "Rotating" means removing the flagged caller_id from the allocation or reducing
 * its weight to the minimum. We pick the simplest defensible action: set the
 * flagged ID's weight to 0 and redistribute proportionally to others.
 *
 * Crash safety: consecutive-tick counts are in memory. After a restart they reset
 * to 0 — worst case we wait CALLER_ID_CONSECUTIVE_FLAG_TICKS extra ticks before
 * acting. This is acceptable and simpler than persisting per-tick state.
 */
public class CallerIdDetector {

    private static final Logger log = LoggerFactory.getLogger(CallerIdDetector.class);

    private final ExposureStore exposure;
    private final Store         store;
    private final SimClient     client;
    private final GovernanceService governance;

    // In-memory tick counters: workflowId → callerId → consecutive-flag count
    // Reset on restart — acceptable (see class-level Javadoc).
    private final Map<String, Map<String, Integer>> consecutiveCounts = new HashMap<>();

    public CallerIdDetector(Connection conn, Store store, SimClient client, GovernanceService governance) {
        this.exposure    = new ExposureStore(conn);
        this.store       = store;
        this.client      = client;
        this.governance  = governance;
    }

    /**
     * Run the detector for one workflow on one tick.
     *
     * @param workflow     workflow detail (for governance policy)
     * @param currentDaySim  current simulated date string "YYYY-MM-DD"
     * @param currentSimTs   current simulated ISO-8601 timestamp
     */
    public void run(WorkflowSummary workflow, String currentDaySim, String currentSimTs) {
        String wfId = workflow.workflowId;
        try {
            List<ExposureStore.CallerIdRow> rows =
                    exposure.queryCallerIdStats(wfId, currentDaySim, BrainConfig.CALLER_ID_WINDOW_DAYS);

            if (rows.size() < 2) return; // need at least 2 caller IDs to compare

            // Compute weighted mean pickup rate across all caller IDs
            long totalDials   = rows.stream().mapToLong(r -> r.dials).sum();
            long totalPickups = rows.stream().mapToLong(r -> r.pickups).sum();
            if (totalDials == 0) return;
            double meanRate = (double) totalPickups / totalDials;

            Map<String, Integer> wfCounts = consecutiveCounts.computeIfAbsent(wfId, k -> new HashMap<>());

            for (ExposureStore.CallerIdRow row : rows) {
                if (row.dials < BrainConfig.MIN_DIALS_FOR_CALLER_ID_FLAG) {
                    wfCounts.put(row.callerId, 0); // reset — not enough data yet
                    continue;
                }
                double gap = meanRate - row.pickupRate();
                if (gap >= BrainConfig.MIN_PICKUP_GAP) {
                    int ticks = wfCounts.merge(row.callerId, 1, Integer::sum);
                    log.info("[{}] caller_id={} pickup_rate={:.3f} gap={:.3f} ticks={}",
                            wfId, row.callerId, row.pickupRate(), gap, ticks);

                    if (ticks >= BrainConfig.CALLER_ID_CONSECUTIVE_FLAG_TICKS) {
                        flag(workflow, row, meanRate, currentSimTs, currentDaySim);
                        wfCounts.put(row.callerId, 0); // reset after acting to avoid repeat actions
                    }
                } else {
                    wfCounts.put(row.callerId, 0); // gap closed — reset streak
                }
            }
        } catch (Exception e) {
            log.error("[{}] CallerIdDetector error: {}", wfId, e.getMessage(), e);
        }
    }

    // ── Act on a flagged caller ID ─────────────────────────────────────────

    private void flag(WorkflowSummary workflow, ExposureStore.CallerIdRow bad,
                      double meanRate, String currentSimTs, String currentDaySim) throws Exception {
        String wfId = workflow.workflowId;
        log.warn("[{}] FLAGGING caller_id={} pickup_rate={:.3f} vs mean={:.3f}",
                wfId, bad.callerId, bad.pickupRate(), meanRate);

        // Write finding to audit_log — always, regardless of what action we take
        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("callerId",      bad.callerId);
        finding.put("dials",         bad.dials);
        finding.put("pickups",       bad.pickups);
        finding.put("pickupRate",    bad.pickupRate());
        finding.put("meanPickupRate", meanRate);
        finding.put("gap",           meanRate - bad.pickupRate());
        finding.put("windowDays",    BrainConfig.CALLER_ID_WINDOW_DAYS);
        finding.put("threshold",     BrainConfig.MIN_PICKUP_GAP);
        store.audit(currentSimTs, wfId, "caller_id_flagged", finding);

        // Decide how to act based on the auto_apply policy
        WorkflowSummary.AutoApply autoApply = (workflow.governance != null)
                ? workflow.governance.autoApply : null;

        boolean canAutoApply = autoApply != null
                && autoApply.enabled
                && autoApply.allowedDims != null
                && autoApply.allowedDims.contains("caller_id");

        if (canAutoApply) {
            tryAutoApply(workflow, bad, currentSimTs, currentDaySim, autoApply.maxShift);
        } else {
            proposeRotation(workflow, bad, currentSimTs, currentDaySim);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Attempt to rotate the flagged caller ID via PUT /workflows/{id}/config (auto-apply).
     * If the shift exceeds max_shift, we split the change: auto-apply the permitted part now
     * and propose the remainder to governance.
     */
    private void tryAutoApply(WorkflowSummary workflow, ExposureStore.CallerIdRow bad,
                               String currentSimTs, String currentDaySim, double maxShift) throws Exception {
        String wfId = workflow.workflowId;
        try {
            com.formi.controller.client.model.ConfigVersion cv = client.getConfig(wfId);
            com.fasterxml.jackson.databind.JsonNode config = cv.config;

            List<String> allIds = workflow.options != null && workflow.options.callerId != null
                    ? workflow.options.callerId : List.of();

            double currentWeight = getCallerIdWeight(config, bad.callerId);
            if (currentWeight <= 0) {
                log.info("[{}] caller_id={} has 0 weight — nothing to rotate.", wfId, bad.callerId);
                return;
            }

            if (currentWeight > maxShift) {
                // Split the change: auto-apply permitted part now, propose remainder to governance
                log.info("[{}] Shift needed {:.3f} > max_shift {:.3f} — splitting change.", wfId, currentWeight, maxShift);

                com.fasterxml.jackson.databind.node.ObjectNode permittedConfig =
                        buildCallerIdConfig(config, allIds, bad.callerId, maxShift);
                Map<String, Object> diffMap = Map.of(
                        "caller_id", "partial shift of " + String.format("%.2f", maxShift) + " away from " + bad.callerId
                );
                client.putConfig(wfId, cv.version, permittedConfig,
                        "Auto-rotate (split part 1): reduced caller_id " + bad.callerId + " by " + String.format("%.2f", maxShift), diffMap);

                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("action", "auto_apply_caller_id_split");
                ok.put("removed_fraction", maxShift);
                ok.put("callerId", bad.callerId);
                store.audit(currentSimTs, wfId, "auto_applied", ok);
                log.info("[{}] Auto-applied permitted split for caller_id={}", wfId, bad.callerId);

                // Propose remainder to governance
                com.fasterxml.jackson.databind.node.ObjectNode fullRemovalConfig =
                        buildCallerIdConfig(config, allIds, bad.callerId, currentWeight);
                proposeRotation(workflow, bad, fullRemovalConfig, currentSimTs, currentDaySim,
                        " (remainder after auto-applying permitted shift " + String.format("%.2f", maxShift) + ")");
            } else {
                // Full removal fits within maxShift
                com.fasterxml.jackson.databind.node.ObjectNode fullRemovalConfig =
                        buildCallerIdConfig(config, allIds, bad.callerId, currentWeight);
                Map<String, Object> diffMap = Map.of(
                        "caller_id", "removed " + bad.callerId
                );
                client.putConfig(wfId, cv.version, fullRemovalConfig,
                        "Auto-rotate: removed underperforming caller_id " + bad.callerId, diffMap);

                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("action",   "auto_apply_caller_id_rotation");
                ok.put("removed",  bad.callerId);
                ok.put("shift",    currentWeight);
                store.audit(currentSimTs, wfId, "auto_applied", ok);
                log.info("[{}] Auto-applied full rotation for caller_id={}", wfId, bad.callerId);
            }

        } catch (com.formi.controller.client.AutoApplyNotPermittedException e) {
            log.warn("[{}] Auto-apply rejected by API — falling back to propose.", wfId);
            com.formi.controller.client.model.ConfigVersion cv = client.getConfig(wfId);
            List<String> allIds = workflow.options != null && workflow.options.callerId != null
                    ? workflow.options.callerId : List.of();
            com.fasterxml.jackson.databind.node.ObjectNode fullRemoval =
                    buildCallerIdConfig(cv.config, allIds, bad.callerId, 1.0);
            proposeRotation(workflow, bad, fullRemoval, currentSimTs, currentDaySim, "");
        }
    }

    /** Helper to build a valid allocations.caller_id config with badCallerId reduced by reduction. */
    private com.fasterxml.jackson.databind.node.ObjectNode buildCallerIdConfig(
            com.fasterxml.jackson.databind.JsonNode currentConfig,
            List<String> allIds,
            String badCallerId,
            double reduction
    ) {
        com.fasterxml.jackson.databind.node.ObjectNode proposed = currentConfig.deepCopy();
        com.fasterxml.jackson.databind.JsonNode allocNode = proposed.path("allocations");
        if (!allocNode.isObject()) return proposed;

        com.fasterxml.jackson.databind.node.ObjectNode allocObj = (com.fasterxml.jackson.databind.node.ObjectNode) allocNode;
        java.util.Iterator<String> cohortNames = allocObj.fieldNames();
        while (cohortNames.hasNext()) {
            String cName = cohortNames.next();
            com.fasterxml.jackson.databind.JsonNode cNode = allocObj.path(cName);
            if (cNode.isObject() && cNode.has("caller_id")) {
                com.fasterxml.jackson.databind.node.ObjectNode didNode = (com.fasterxml.jackson.databind.node.ObjectNode) cNode.path("caller_id");
                double currentBadWeight = didNode.path(badCallerId).asDouble(0.0);
                if (currentBadWeight <= 0) continue;

                double actualReduction = Math.min(currentBadWeight, reduction);
                double newBadWeight = currentBadWeight - actualReduction;

                List<String> healthy = allIds.stream()
                        .filter(id -> !id.equals(badCallerId))
                        .toList();
                if (healthy.isEmpty()) continue;

                double currentHealthySum = 0.0;
                for (String h : healthy) {
                    currentHealthySum += didNode.path(h).asDouble(0.0);
                }

                com.fasterxml.jackson.databind.node.ObjectNode newDidMap = mapper.createObjectNode();
                if (newBadWeight > 1e-6) {
                    newDidMap.put(badCallerId, newBadWeight);
                }

                for (String h : healthy) {
                    double oldW = didNode.path(h).asDouble(0.0);
                    double addW = (currentHealthySum > 1e-6)
                            ? actualReduction * (oldW / currentHealthySum)
                            : actualReduction / healthy.size();
                    newDidMap.put(h, oldW + addW);
                }

                ((com.fasterxml.jackson.databind.node.ObjectNode) cNode).set("caller_id", newDidMap);
            }
        }
        return proposed;
    }

    private double getCallerIdWeight(com.fasterxml.jackson.databind.JsonNode config, String callerId) {
        com.fasterxml.jackson.databind.JsonNode alloc = config.path("allocations").path("default").path("caller_id");
        if (alloc.isObject()) {
            return alloc.path(callerId).asDouble(0.0);
        }
        return 0.0;
    }

    /** Submit a formal approval proposal to rotate the caller ID. */
    private void proposeRotation(WorkflowSummary workflow, ExposureStore.CallerIdRow bad,
                                  String currentSimTs, String currentDaySim) throws Exception {
        String wfId = workflow.workflowId;
        com.formi.controller.client.model.ConfigVersion cv = client.getConfig(wfId);
        List<String> allIds = workflow.options != null && workflow.options.callerId != null
                ? workflow.options.callerId : List.of();
        com.fasterxml.jackson.databind.node.ObjectNode fullRemoval =
                buildCallerIdConfig(cv.config, allIds, bad.callerId, 1.0);
        proposeRotation(workflow, bad, fullRemoval, currentSimTs, currentDaySim, "");
    }

    private void proposeRotation(WorkflowSummary workflow, ExposureStore.CallerIdRow bad,
                                  com.fasterxml.jackson.databind.node.ObjectNode proposed,
                                  String currentSimTs, String currentDaySim, String note) throws Exception {
        String wfId = workflow.workflowId;
        com.formi.controller.client.model.ConfigVersion cv = client.getConfig(wfId);

        Map<String, Object> diff = Map.of(
                "caller_id", "remove " + bad.callerId + note
        );
        Map<String, Object> dryRun = new LinkedHashMap<>();
        dryRun.put("flaggedCallerId",    bad.callerId);
        dryRun.put("dialCount",          bad.dials);
        dryRun.put("pickupRate",         bad.pickupRate());
        dryRun.put("workflowMeanPickup", bad.pickupRate());
        dryRun.put("windowDays",         BrainConfig.CALLER_ID_WINDOW_DAYS);

        String rationale = String.format(
                "Caller-ID %s has pickup rate %.1f%% vs workflow mean. " +
                "Flag persisted for %d consecutive ticks (threshold: %d). " +
                "Proposing removal%s to protect overall pickup rate.",
                bad.callerId,
                bad.pickupRate() * 100,
                BrainConfig.CALLER_ID_CONSECUTIVE_FLAG_TICKS,
                BrainConfig.CALLER_ID_CONSECUTIVE_FLAG_TICKS,
                note
        );

        governance.propose(workflow, cv.version, proposed,
                "Rotate caller_id: remove underperforming " + bad.callerId + note,
                diff, (int)(bad.dials * 0.1), dryRun, 0.4, rationale, null, currentSimTs);
    }
}
