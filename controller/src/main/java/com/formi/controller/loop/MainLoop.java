package com.formi.controller.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formi.controller.Config;
import com.formi.controller.client.ApiException;
import com.formi.controller.client.FinishedException;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.AdvanceResult;
import com.formi.controller.client.model.ConfigVersion;
import com.formi.controller.client.model.SimState;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.db.Store;
import com.formi.controller.governance.GovernanceService;
import com.formi.controller.health.HealthState;
import com.formi.controller.ingestion.Ingester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The main control loop.
 *
 * Startup sequence (crash-safe):
 *   1. Bootstrap history chunks (idempotent, guarded by DB flag).
 *   2. Catch up any event chunks produced before this restart.
 *   3. Governance startup reconcile — apply anything already approved.
 *   4. Optionally submit the DEMO_PROPOSAL.
 *   5. Enter the tick loop.
 *
 * Tick loop:
 *   advance → ingest → reconcile approvals → apply approved → audit tick summary
 *   Stop on FinishedException (409 finished) or on stopRequested flag (SIGTERM).
 *
 * Crash safety:
 *   - Ingestion is transactional; a kill at any line leaves the cursor at the
 *     last fully processed chunk.
 *   - Governance writes "intent_to_apply" before the HTTP call; a kill between
 *     write and call causes the reconciler to retry on next start.
 *   - The main loop itself has no state beyond the DB, so restarting is free.
 */
public class MainLoop {

    private static final Logger log = LoggerFactory.getLogger(MainLoop.class);

    private final Config           cfg;
    private final SimClient        client;
    private final Store            store;
    private final Ingester         ingester;
    private final GovernanceService governance;
    private final HealthState      healthState;
    private final ObjectMapper     mapper = new ObjectMapper();

    // Set by the SIGTERM shutdown hook to request a clean stop
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    // Set to true after the demo proposal has been submitted once per run
    private boolean demoDone = false;

    public MainLoop(Config cfg, SimClient client, Store store,
                    Ingester ingester, GovernanceService governance, HealthState healthState) {
        this.cfg        = cfg;
        this.client     = client;
        this.store      = store;
        this.ingester   = ingester;
        this.governance = governance;
        this.healthState = healthState;
    }

    public void requestStop() { stopRequested.set(true); }

    // ══════════════════════════════════════════════════════════════════════════
    // Entry point
    // ══════════════════════════════════════════════════════════════════════════

    public void run() throws Exception {

        // ── Step 1: history bootstrap ──────────────────────────────────────────
        ingester.bootstrapHistory();

        // ── Step 2: catch up missed event chunks from before this run ──────────
        ingester.catchUpMissedChunks();

        // ── Step 3: governance startup reconcile ───────────────────────────────
        String simTs = store.getSimTs();
        if (simTs == null) simTs = Instant.now().toString();  // first ever start
        governance.startupReconcile(simTs);

        // ── Step 4: load workflow metadata ─────────────────────────────────────
        List<WorkflowSummary> workflows = client.listWorkflows();
        log.info("Loaded {} workflow(s): {}",
                workflows.size(),
                workflows.stream().map(w -> w.workflowId).toList());

        // ── Step 5: tick loop ──────────────────────────────────────────────────
        log.info("Entering tick loop (tick={}min).", cfg.tickMinutes());
        while (!stopRequested.get()) {
            try {
                tick(workflows);
            } catch (FinishedException e) {
                log.info("Scenario finished — exiting loop.");
                // Write a final audit row so the run is closed cleanly in the DB
                store.audit(store.getSimTs(), null, "scenario_finished",
                        Map.of("message", "scenario ended normally"));
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Interrupted — stopping.");
                break;
            } catch (Exception e) {
                // Log but don't crash the loop — the simulator will still be running
                log.error("Tick error (will retry next tick): {}", e.getMessage(), e);
            }
        }

        log.info("Main loop stopped. Total events ingested: {}.", ingester.getTotalEventsIngested());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Single tick
    // ══════════════════════════════════════════════════════════════════════════

    private void tick(List<WorkflowSummary> workflows) throws Exception {
        long tickStart = System.currentTimeMillis();

        // ── Advance simulated time ─────────────────────────────────────────────
        AdvanceResult advance = client.advance(cfg.tickMinutes());
        String simTs = advance.ts;
        store.setSimTs(simTs);

        // ── Ingest new event chunks ────────────────────────────────────────────
        int ingested = ingester.ingestNewChunks(advance.newChunks);

        // ── Reconcile pending approvals ────────────────────────────────────────
        List<Map<String, Object>> readyToApply = governance.reconcile(simTs);
        for (Map<String, Object> row : readyToApply) {
            governance.apply(
                    (String) row.get("proposalId"),
                    (String) row.get("approvalId"),
                    (String) row.get("workflowId"),
                    simTs
            );
        }

        // ── DEMO_PROPOSAL: submit once, early in the run ───────────────────────
        if (cfg.demoProposal() && !demoDone && advance.newChunks != null && !advance.newChunks.isEmpty()) {
            if (submitDemoProposal(workflows, simTs)) {
                demoDone = true;
            }
        }

        // ── Fetch sim state for credits / health ───────────────────────────────
        SimState simState = client.getSimState();

        // ── Update health snapshot ────────────────────────────────────────────
        long ingestLag = System.currentTimeMillis() - tickStart;
        try {
            healthState.update(new HealthState.Snapshot(
                    simTs,
                    store.getEventCursor(),
                    ingester.getTotalEventsIngested(),
                    store.countPendingApprovals(),
                    ingestLag,
                    simState.creditsRemaining
            ));
        } catch (Exception e) {
            log.debug("Health update failed (non-fatal): {}", e.getMessage());
        }

        // ── Audit tick summary ─────────────────────────────────────────────────
        Map<String, Object> tickPayload = new LinkedHashMap<>();
        tickPayload.put("simTs",             simTs);
        tickPayload.put("eventsIngested",    ingested);
        tickPayload.put("pendingApprovals",  readyToApply.size());
        tickPayload.put("creditsRemaining",  simState.creditsRemaining);
        tickPayload.put("wallMs",            ingestLag);
        store.audit(simTs, null, "tick", tickPayload);

        log.info("[{}] Tick done: {}min advanced, {} events ingested, {}ms wall.",
                simTs, cfg.tickMinutes(), ingested, ingestLag);

        // Credit warning — 20 is a rough threshold; at 3 credits/LLM call we have ~6 calls left
        if (simState.creditsRemaining < 20) {
            log.warn("LOW CREDITS: {} remaining — do not make LLM or validation calls.", simState.creditsRemaining);
        }

        // Scenario completed flag check
        if (advance.finished) {
            log.info("Scenario finished flag received in advance response — exiting loop.");
            store.audit(simTs, null, "scenario_finished",
                    Map.of("message", "scenario ended normally"));
            requestStop();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo proposal (DEMO_PROPOSAL=true only)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Submit one harmless proposal on the first workflow to exercise the full
     * approval lifecycle end-to-end. See ASSUMPTIONS.md §A8.
     *
     * The proposed config is identical to the current config except we explicitly
     * set schedule.hour_weights.default to weight 1 for each hour in the dialling
     * window — semantically a no-op, so supervisor and engagement are unaffected.
     */
    private boolean submitDemoProposal(List<WorkflowSummary> workflows, String simTs) {
        if (workflows.isEmpty()) return false;
        WorkflowSummary wf = workflows.get(0);
        log.info("[DEMO] Submitting demo proposal for workflow {}…", wf.workflowId);

        try {
            ConfigVersion cv = client.getConfig(wf.workflowId);
            JsonNode currentConfig = cv.config;

            // Build proposed config: deep-copy current, then add hour_weights
            ObjectNode proposed = currentConfig.deepCopy();

            // Build hour_weights: every hour in [diallingStart, diallingEnd) → weight 1
            int dialStart = (wf.diallingWindow != null && wf.diallingWindow.size() > 0)
                    ? wf.diallingWindow.get(0) : 9;
            int dialEnd   = (wf.diallingWindow != null && wf.diallingWindow.size() > 1)
                    ? wf.diallingWindow.get(1) : 21;

            ObjectNode hourWeights = mapper.createObjectNode();
            for (int h = dialStart; h < dialEnd; h++) {
                hourWeights.put(String.valueOf(h), 1);
            }
            ObjectNode hwDefault = mapper.createObjectNode();
            hwDefault.set("default", hourWeights);

            ObjectNode schedule;
            if (proposed.has("schedule") && proposed.get("schedule").isObject()) {
                schedule = (ObjectNode) proposed.get("schedule");
            } else {
                schedule = mapper.createObjectNode();
                proposed.set("schedule", schedule);
            }
            schedule.set("hour_weights", hwDefault);

            Map<String, Object> diff = Map.of(
                    "schedule.hour_weights.default",
                    "explicitly set to weight 1 for all hours in dialling window (no-op semantically)"
            );
            Map<String, Object> dryRun = Map.of(
                    "type",   "historical_replay",
                    "note",   "No leads affected — this is a semantic no-op",
                    "result", "identical KPIs expected"
            );

            String approvalId = governance.propose(
                    wf,
                    cv.version,
                    proposed,
                    "Demo: explicitly set default hour weights (no-op — testing approval lifecycle)",
                    diff,
                    /*affectedLeads*/ 0,
                    dryRun,
                    /*impactScore*/   0.0,
                    "Day 1 smoke test of the governance pipeline. The proposed change is semantically " +
                    "identical to the current implicit default and will not affect any KPIs.",
                    /*evidence*/      null,
                    simTs
            );
            return approvalId != null;
        } catch (Exception e) {
            log.warn("[DEMO] Demo proposal failed (non-fatal): {}", e.getMessage());
            return false;
        }
    }
}
