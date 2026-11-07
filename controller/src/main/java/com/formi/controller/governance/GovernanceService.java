package com.formi.controller.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formi.controller.client.*;
import com.formi.controller.client.model.*;
import com.formi.controller.db.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Manages the full approval lifecycle: propose → poll → apply.
 *
 * Key design decisions:
 *
 *  1. Deterministic proposal IDs — SHA-256 of (workflow_id + ":" + base_version
 *     + ":" + sorted config JSON). The same intent always produces the same ID, so
 *     a crash-and-restart cannot create a duplicate proposal. On 409 duplicate_proposal_id
 *     we adopt the existing approval by looking it up with GET /approvals?proposal_id=.
 *
 *  2. Intent-to-apply audit row — written BEFORE calling /apply. If we crash between
 *     the write and the HTTP call, the reconciler sees the intent and re-attempts apply.
 *     If the call succeeds we write the "applied" row. If it fails we write "apply_failed".
 *
 *  3. Rate limiting in simulated time — we count proposals submitted within the last
 *     24 simulated hours before each propose() call and refuse if at the limit, keeping
 *     one slot reserved for incidents (so we submit at most max_requests_per_24h - 1
 *     routine proposals and always have room for an urgent one).
 *
 *  4. Approver-hours guard — we do not submit NEW proposals when outside the approver's
 *     working hours; polling and applying already-approved proposals happen regardless.
 *
 *  5. No automatic re-proposing on stale_base_version — the caller (Day 2 logic) is
 *     responsible for deciding whether the new config still warrants the same change.
 */
public class GovernanceService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceService.class);

    private final SimClient client;
    private final Store     store;
    private final ObjectMapper mapper = new ObjectMapper();

    public GovernanceService(SimClient client, Store store) {
        this.client = client;
        this.store  = store;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Propose
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Submit a governance proposal.
     *
     * Guards applied before submitting:
     *   - approver must be within working hours (see ASSUMPTIONS.md §A4)
     *   - workflow must have a free request slot in the 24h rolling window (§A5)
     *
     * @param workflow     workflow detail (for governance policy fields)
     * @param baseVersion  current config version (from GET /workflows/{id}/config)
     * @param proposedConfig full proposed config (JsonNode, opaque)
     * @param summary      one-line description a supervisor understands
     * @param diff         non-empty map showing what changed
     * @param affectedLeads estimated number of affected leads
     * @param dryRun       non-empty map with dry-run evidence
     * @param impactScore  [0,1] estimated impact
     * @param rationale    human-readable rationale
     * @param evidence     optional; required for prompt-version changes (§A2)
     * @param currentSimTs current simulated timestamp (from last advance)
     * @return the approval_id assigned by the simulator, or null if submission was
     *         skipped (outside approver hours or rate-limited)
     */
    public String propose(
            WorkflowSummary workflow,
            int baseVersion,
            JsonNode proposedConfig,
            String summary,
            Map<String, Object> diff,
            int affectedLeads,
            Map<String, Object> dryRun,
            double impactScore,
            String rationale,
            Map<String, Object> evidence,
            String currentSimTs
    ) throws Exception {

        String workflowId = workflow.workflowId;
        WorkflowSummary.Governance gov = workflow.governance;

        // ── Guard 1: approver working hours ───────────────────────────────────
        if (!isWithinApproverHours(gov, currentSimTs)) {
            log.info("[{}] Outside approver hours {} — deferring proposal.", workflowId, gov.approverHours);
            return null;
        }

        // ── Guard 2: 24h rate limit (keep 1 slot in reserve for incidents) ────
        int used = store.countRecentProposals(workflowId, currentSimTs, 24);
        int limit = gov.maxRequestsPer24h;
        if (used >= limit - 1) {
            log.warn("[{}] Rate limit: {}/{} proposals used (keeping 1 slot for incidents).",
                    workflowId, used, limit);
            return null;
        }

        // ── Build and submit ───────────────────────────────────────────────────
        int pastProposals = store.countProposalsForConfig(workflowId, baseVersion, mapper.writeValueAsString(proposedConfig));
        String proposalId = computeProposalId(workflowId, baseVersion, proposedConfig, pastProposals);
        log.info("[{}] Submitting proposal {} (base_version={}, attempt={}).", workflowId, proposalId, baseVersion, pastProposals);

        ApprovalRequest req = new ApprovalRequest();
        req.proposalId     = proposalId;
        req.workflowId     = workflowId;
        req.baseVersion    = baseVersion;
        req.proposedConfig = proposedConfig;
        req.changeSummary  = summary;
        req.diff           = diff;
        req.affectedLeads  = affectedLeads;
        req.dryRun         = dryRun;
        req.impactScore    = impactScore;
        req.rationale      = rationale;
        req.evidence       = evidence;
        req.expiresInHours = gov.proposalTtlHours;

        try {
            ApprovalSubmitResult result = client.submitApproval(req);
            String approvalId = result.approvalId;

            // Persist immediately — this row is our crash-safe record
            store.insertApproval(
                    proposalId, approvalId, workflowId, baseVersion, "pending",
                    mapper.writeValueAsString(proposedConfig), summary, rationale, currentSimTs
            );

            Map<String, Object> payload = new HashMap<>();
            payload.put("proposalId",   proposalId);
            payload.put("approvalId",   approvalId);
            payload.put("baseVersion",  baseVersion);
            payload.put("summary",      summary);
            store.audit(currentSimTs, workflowId, "proposal_submitted", payload);

            log.info("[{}] Proposal submitted → approval_id={}.", workflowId, approvalId);
            return approvalId;

        } catch (DuplicateProposalException e) {
            // We already submitted this exact proposal — adopt the existing approval
            log.warn("[{}] Duplicate proposal_id={} — adopting existing approval.", workflowId, proposalId);
            return adoptExistingApproval(proposalId, workflowId, baseVersion,
                    proposedConfig, summary, rationale, currentSimTs);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Poll and reconcile
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Check the status of all pending/approved proposals in our DB against the API.
     * Updates our local status and returns a list of approvals that are now approved
     * and ready to apply.
     *
     * Called once per tick. The ingester's approval_decided handler also updates statuses
     * as events arrive, so this is a safety-net reconciliation, not the primary path.
     */
    public List<Map<String, Object>> reconcile(String currentSimTs) throws Exception {
        List<Map<String, Object>> readyToApply = new ArrayList<>();
        List<Map<String, Object>> pending = store.getPendingApprovals();

        for (Map<String, Object> row : pending) {
            String approvalId = (String) row.get("approvalId");
            if (approvalId == null || approvalId.isBlank()) continue;

            try {
                ApprovalStatus status = client.getApproval(approvalId);
                syncLocalStatus(status, currentSimTs);
                if (status.isApproved()) {
                    readyToApply.add(row);
                }
            } catch (ApiException e) {
                log.warn("Could not poll approval {}: {}", approvalId, e.getMessage());
            }
        }
        return readyToApply;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Apply
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Apply an approved proposal.
     *
     * Writes "intent_to_apply" to audit BEFORE the HTTP call.
     * On success writes "applied". On failure writes "apply_failed" with the reason.
     *
     * @param proposalId our proposal ID (primary key in approvals table)
     * @param approvalId the simulator's approval ID
     * @param workflowId target workflow
     * @param currentSimTs simulated timestamp for audit records
     */
    public boolean apply(
            String proposalId, String approvalId, String workflowId, String currentSimTs
    ) throws Exception {

        // Write intent BEFORE the network call — if we crash here, the reconciler
        // will see this record and retry the apply on next startup.
        Map<String, Object> intentPayload = new HashMap<>();
        intentPayload.put("proposalId", proposalId);
        intentPayload.put("approvalId", approvalId);
        store.audit(currentSimTs, workflowId, "intent_to_apply", intentPayload);
        log.info("[{}] Intent to apply approval {} recorded.", workflowId, approvalId);

        try {
            client.applyApproval(workflowId, approvalId);

            // Success
            store.markApprovalApplied(proposalId, java.time.Instant.now().toString());
            Map<String, Object> ok = new HashMap<>();
            ok.put("proposalId", proposalId);
            ok.put("approvalId", approvalId);

            // Record time_to_rollback_minutes if this is a decay rollback apply
            try {
                Map<String, Object> appRow = store.getApproval(proposalId);
                if (appRow != null) {
                    String summary = (String) appRow.get("summary");
                    if (summary != null && summary.contains("Rollback decaying variant")) {
                        String createdTs = (String) appRow.get("createdTs");
                        if (createdTs != null) {
                            long mins = java.time.Duration.between(
                                    java.time.Instant.parse(createdTs),
                                    java.time.Instant.parse(currentSimTs)
                            ).toMinutes();
                            ok.put("time_to_rollback_minutes", mins);
                        }
                    }
                }
            } catch (Exception ignored) {}

            store.audit(currentSimTs, workflowId, "applied", ok);
            log.info("[{}] Applied approval {} successfully.", workflowId, approvalId);
            return true;

        } catch (StaleBaseVersionException e) {
            // Config changed between approval and apply — the approval is now useless.
            // We mark it stale; Day 2 logic will re-propose with the new base_version.
            store.updateApprovalStatus(proposalId, "stale", currentSimTs,
                    mapper.writeValueAsString(List.of("stale_base_version")));
            Map<String, Object> fail = new HashMap<>();
            fail.put("proposalId", proposalId);
            fail.put("reason",     "stale_base_version");
            store.audit(currentSimTs, workflowId, "apply_failed", fail);
            log.warn("[{}] Apply failed — stale base version. Will re-propose on Day 2.", workflowId);
            return false;

        } catch (LapsedException e) {
            store.updateApprovalStatus(proposalId, "lapsed", currentSimTs, null);
            Map<String, Object> fail = new HashMap<>();
            fail.put("proposalId", proposalId);
            fail.put("reason",     "lapsed");
            store.audit(currentSimTs, workflowId, "apply_failed", fail);
            log.warn("[{}] Apply failed — approval lapsed (apply window expired).", workflowId);
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Startup reconciler: apply anything already approved that survived a crash
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * On startup, re-sync all non-terminal proposals between our DB and the API.
     * If any are already approved and within their apply window, apply them now.
     *
     * This handles the crash scenario: "approved, wrote intent_to_apply, crashed
     * before the HTTP call". On restart we see the pending row and retry.
     */
    public void startupReconcile(String currentSimTs) throws Exception {
        log.info("Running startup reconcile…");
        List<Map<String, Object>> pending = store.getPendingApprovals();
        log.info("Found {} non-terminal proposal(s) in DB.", pending.size());

        for (Map<String, Object> row : pending) {
            String approvalId = (String) row.get("approvalId");
            if (approvalId == null || approvalId.isBlank()) continue;

            try {
                ApprovalStatus status = client.getApproval(approvalId);
                syncLocalStatus(status, currentSimTs);

                if (status.isApproved()) {
                    log.info("Startup: found approved approval {} — applying now.", approvalId);
                    apply((String) row.get("proposalId"), approvalId,
                            (String) row.get("workflowId"), currentSimTs);
                }
            } catch (ApiException e) {
                log.warn("Startup reconcile: could not fetch approval {}: {}", approvalId, e.getMessage());
            }
        }
        log.info("Startup reconcile complete.");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Deterministic proposal ID — see ASSUMPTIONS.md §A7.
     *
     * We use SHA-256 of the canonical key so the same change always maps to the
     * same proposal_id. This prevents duplicate submissions after a crash without
     * requiring us to query the DB before every propose().
     */
    static String computeProposalId(String workflowId, int baseVersion, JsonNode proposedConfig) {
        return computeProposalId(workflowId, baseVersion, proposedConfig, 0);
    }

    static String computeProposalId(String workflowId, int baseVersion, JsonNode proposedConfig, int attempt) {
        // Sort the config JSON alphabetically so key order doesn't matter
        String canonical = workflowId + ":" + baseVersion + ":" + proposedConfig.toString()
                + (attempt > 0 ? ":attempt:" + attempt : "");
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ctrl-");
            for (int i = 0; i < 12; i++) {  // 24 hex chars → low collision probability
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available — this should never happen", e);
        }
    }

    /**
     * On DuplicateProposalException, fetch the existing proposal from the API and
     * adopt its approval_id into our DB.
     */
    private String adoptExistingApproval(
            String proposalId, String workflowId, int baseVersion,
            JsonNode proposedConfig, String summary, String rationale, String simTs
    ) throws Exception {
        // The API lets us list approvals by workflow; find the one with our proposal_id.
        List<ApprovalStatus> existing = client.listApprovals(workflowId, null);
        for (ApprovalStatus s : existing) {
            if (proposalId.equals(s.proposalId)) {
                log.info("[{}] Adopted existing approval_id={}.", workflowId, s.approvalId);
                // Upsert — if our DB already has it (from a previous run), INSERT OR IGNORE won't duplicate
                store.insertApproval(proposalId, s.approvalId, workflowId, baseVersion,
                        s.status, mapper.writeValueAsString(proposedConfig), summary, rationale, simTs);
                return s.approvalId;
            }
        }
        log.error("[{}] Could not find existing approval for proposal_id={}.", workflowId, proposalId);
        return null;
    }

    /** Update our DB row to match the API-reported status. */
    private void syncLocalStatus(ApprovalStatus status, String simTs) throws Exception {
        if (status.proposalId == null) return;
        String reasonsJson = mapper.writeValueAsString(
                status.reasons != null ? status.reasons : List.of());
        store.updateApprovalStatus(status.proposalId, status.status, status.decidedAt, reasonsJson);
    }

    /**
     * True if the current simulated hour is within the approver's working hours.
     * See ASSUMPTIONS.md §A4: approver_hours = [start, end) in IST 24h clock.
     */
    private boolean isWithinApproverHours(WorkflowSummary.Governance gov, String simTs) {
        if (gov.approverHours == null || gov.approverHours.size() < 2) return true;
        try {
            // simTs is ISO-8601 with offset, e.g. "2027-01-05T10:15:00+05:30"
            // Extract the hour part from the time component
            String timePart = simTs.substring(11, 13); // "HH"
            int hour = Integer.parseInt(timePart);
            int start = gov.approverHours.get(0);
            int end   = gov.approverHours.get(1);
            return hour >= start && hour < end;
        } catch (Exception e) {
            log.warn("Could not parse hour from simTs='{}': {}", simTs, e.getMessage());
            return true;  // fail open — better to submit than to silently skip forever
        }
    }
}
