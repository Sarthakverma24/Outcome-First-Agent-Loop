package com.formi.controller.brain;

import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.governance.GovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

/**
 * Day 2 Brain — orchestrates all analysis components.
 *
 * Called once per tick (from MainLoop) after ingestion and governance reconcile.
 * Each component is independent: a failure in one does not stop the others.
 *
 * Order of operations on each tick:
 *   1. SupervisorEditHandler — contaminate experiments if supervisor edits arrived.
 *   2. CallerIdDetector      — flag and rotate underperforming caller IDs.
 *   3. HallucinationDetector — detect and propose rollback for prompt quality issues.
 *   4. ExperimentEngine      — run A/B experiments (voice, welcome_message).
 *   5. DecayWatcher          — monitor applied winners for performance decay.
 *   6. HourWeightOptimizer   — optimise schedule weights from history.
 *
 * Design: each component is stateless except for in-memory consecutive-tick counters
 * that reset safely on restart (a brief delay is the worst consequence).
 */
public class Brain {

    private static final Logger log = LoggerFactory.getLogger(Brain.class);

    private final SupervisorEditHandler supervisorEditHandler;
    private final CallerIdDetector      callerIdDetector;
    private final HallucinationDetector hallucinationDetector;
    private final ExperimentEngine      experimentEngine;
    private final DecayWatcher          decayWatcher;
    private final HourWeightOptimizer   hourWeightOptimizer;

    // Tracks the simTs from the previous tick, used by SupervisorEditHandler
    // to find audit rows that arrived *this* tick.
    private String prevSimTs = null;

    public Brain(Connection conn, Store store, SimClient client, GovernanceService governance) {
        this.supervisorEditHandler = new SupervisorEditHandler(conn, store);
        this.callerIdDetector      = new CallerIdDetector(conn, store, client, governance);
        this.hallucinationDetector = new HallucinationDetector(conn, store, client, governance);
        this.experimentEngine      = new ExperimentEngine(conn, store, client, governance);
        this.decayWatcher          = new DecayWatcher(conn, store, client, governance);
        this.hourWeightOptimizer   = new HourWeightOptimizer(conn, store, client, governance);
    }

    /**
     * Run all brain components for one tick.
     *
     * @param workflows    all workflows loaded from the API
     * @param currentSimTs ISO-8601 simulated timestamp after advance
     */
    public void tick(List<WorkflowSummary> workflows, String currentSimTs) {
        // Extract date portion "YYYY-MM-DD" from the ISO-8601 timestamp
        String currentDaySim = currentSimTs.length() >= 10
                ? currentSimTs.substring(0, 10) : currentSimTs;

        for (WorkflowSummary workflow : workflows) {
            String wfId = workflow.workflowId;
            log.debug("[{}] Brain tick at {}", wfId, currentSimTs);

            // 1. Supervisor edit contamination check (must run first — before we propose)
            String prev = prevSimTs != null ? prevSimTs : "";
            safeRun(wfId, "supervisor",    () -> supervisorEditHandler.run(wfId, prev, currentSimTs));

            // 2. Caller-ID health
            safeRun(wfId, "caller_id",     () -> callerIdDetector.run(workflow, currentDaySim, currentSimTs));

            // 3. Hallucination detection
            safeRun(wfId, "hallucination", () -> hallucinationDetector.run(workflow, currentDaySim, currentSimTs));

            // 4. Experiments
            safeRun(wfId, "experiments",   () -> experimentEngine.run(workflow, currentSimTs, currentDaySim));

            // 5. Decay monitoring
            safeRun(wfId, "decay",         () -> decayWatcher.run(workflow, currentSimTs, currentDaySim));

            // 6. Hour weights
            safeRun(wfId, "hour_weights",  () -> hourWeightOptimizer.run(workflow, currentSimTs, currentDaySim));
        }

        prevSimTs = currentSimTs;
    }

    /** Run a lambda; catch and log any exception so one component cannot crash the loop. */
    private void safeRun(String wfId, String component, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("[{}][{}] Brain component failed: {}", wfId, component, e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
