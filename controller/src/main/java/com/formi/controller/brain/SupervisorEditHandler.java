package com.formi.controller.brain;

import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.WorkflowSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Handles supervisor edits and expired/stale proposals.
 *
 * Supervisor edits (actor=supervisor in config_changed events) are already written
 * to the audit_log by the Day 1 ingester. This class adds the Day 2 response:
 *
 *   1. When a supervisor edit is detected for a workflow, any running experiment on
 *      that workflow is marked "contaminated" — its data is now mixed between two
 *      config states and cannot be used for a clean causal comparison.
 *
 *   2. Expired proposals (the simulator may reject a proposal TTL-expired) are
 *      detected during governance reconcile. The experiment that generated the
 *      proposal is marked "expired" and re-queued by resetting its status to
 *      "running" with a new deterministic experiment_id (using current sim_ts).
 *
 *   3. Stale proposals: handled by GovernanceService — it marks them "stale".
 *      Here we detect "stale" proposals and expire the linked experiment so the
 *      ExperimentEngine starts fresh on the next tick.
 *
 * Hard rule: we NEVER overwrite a supervisor edit. We only record it and react.
 */
public class SupervisorEditHandler {

    private static final Logger log = LoggerFactory.getLogger(SupervisorEditHandler.class);

    private final Connection conn;
    private final Store      store;

    public SupervisorEditHandler(Connection conn, Store store) {
        this.conn  = conn;
        this.store = store;
    }

    /**
     * Run supervisor-edit handling for one tick.
     *
     * We look at audit_log entries of kind "supervisor_edit" that arrived since
     * the last tick (using the simTs gap) and contaminate any running experiments.
     *
     * Also sweeps for stale/expired proposals and marks linked experiments for re-queue.
     *
     * @param workflowId     workflow to check
     * @param prevSimTs      simTs from the previous tick (audit rows with ts_sim > prev are new)
     * @param currentSimTs   current simTs
     */
    public void run(String workflowId, String prevSimTs, String currentSimTs) {
        try {
            handleSupervisorEdits(workflowId, prevSimTs, currentSimTs);
            handleStaleExpiredProposals(workflowId, currentSimTs);
        } catch (Exception e) {
            log.error("[{}] SupervisorEditHandler error: {}", workflowId, e.getMessage(), e);
        }
    }

    // ── Supervisor edits ──────────────────────────────────────────────────

    private void handleSupervisorEdits(String workflowId, String prevSimTs, String currentSimTs)
            throws Exception {
        // Count supervisor_edit audit entries since last tick
        int editCount = countSupervisorEdits(workflowId, prevSimTs);
        if (editCount == 0) return;

        log.warn("[{}] {} supervisor edit(s) detected — contaminating running experiments.", workflowId, editCount);

        // Mark all running/proposed experiments for this workflow as contaminated
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE experiments
                SET status='contaminated', contaminated_reason=?
                WHERE workflow_id=?
                  AND status IN ('running','winner_proposed','winner_pending')
                """)) {
            ps.setString(1, "supervisor_edit_at_" + currentSimTs);
            ps.setString(2, workflowId);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("[{}] Contaminated {} experiment(s).", workflowId, rows);
                store.audit(currentSimTs, workflowId, "experiments_contaminated", Map.of(
                        "count",  rows,
                        "reason", "supervisor_edit"
                ));
            }
        }
    }

    /** Count supervisor_edit audit entries for a workflow since prevSimTs. */
    private int countSupervisorEdits(String workflowId, String prevSimTs) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM audit_log
                WHERE workflow_id=? AND kind='supervisor_edit'
                  AND ts_sim > ?
                """)) {
            ps.setString(1, workflowId);
            ps.setString(2, prevSimTs != null ? prevSimTs : "");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ── Stale and expired proposals ───────────────────────────────────────

    /**
     * Find proposals with status "stale" or "expired" that are linked to experiments,
     * and mark those experiments for re-queue (back to "running" status so the
     * ExperimentEngine will start a new look with a new deterministic ID next tick).
     */
    private void handleStaleExpiredProposals(String workflowId, String currentSimTs) throws Exception {
        // Find experiments linked to stale or expired proposals
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT e.experiment_id FROM experiments e
                JOIN approvals a ON a.proposal_id = e.proposal_id
                WHERE e.workflow_id = ?
                  AND a.status IN ('stale', 'expired')
                  AND e.status IN ('winner_proposed', 'winner_pending')
                """)) {
            ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String expId = rs.getString(1);
                    // Reset to "expired" — ExperimentEngine will start a fresh one
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE experiments SET status='expired' WHERE experiment_id=?")) {
                        upd.setString(1, expId);
                        upd.executeUpdate();
                    }
                    store.audit(currentSimTs, workflowId, "experiment_expired", Map.of(
                            "experimentId", expId,
                            "reason", "linked_proposal_stale_or_expired"
                    ));
                    log.info("[{}] Experiment {} marked expired (proposal stale/expired).", workflowId, expId);
                }
            }
        }
    }

    /**
     * Mark an experiment's status as "winner_applied" when we detect that its
     * linked approval has been applied. Called by Brain after each successful apply.
     */
    public void markExperimentApplied(String proposalId, String applySimTs) {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE experiments SET status='winner_applied', apply_sim_ts=?
                WHERE proposal_id=?
                """)) {
            ps.setString(1, applySimTs);
            ps.setString(2, proposalId);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("markExperimentApplied failed for proposalId={}: {}", proposalId, e.getMessage());
        }
    }
}
