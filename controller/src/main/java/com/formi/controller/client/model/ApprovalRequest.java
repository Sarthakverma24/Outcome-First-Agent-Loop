package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Request body for POST /approvals.
 *
 * All fields are described in SIMULATOR.md §4.1.
 * We hold proposed_config as a JsonNode so we can pass through the opaque
 * object without accidentally round-tripping through our own schema.
 */
public class ApprovalRequest {
    /** Deterministic idempotency key — see GovernanceService.proposalId(). */
    @JsonProperty("proposal_id")
    public String proposalId;

    @JsonProperty("workflow_id")
    public String workflowId;

    /** Must equal the current config version at time of proposal. */
    @JsonProperty("base_version")
    public int baseVersion;

    /** Full config object (opaque to Day 1 controller). */
    @JsonProperty("proposed_config")
    public JsonNode proposedConfig;

    /** One line a supervisor understands. Must not be empty. */
    @JsonProperty("change_summary")
    public String changeSummary;

    /** Free-form diff object. Must not be empty. */
    @JsonProperty("diff")
    public Map<String, Object> diff;

    /** Our best estimate of affected leads. */
    @JsonProperty("affected_leads")
    public int affectedLeads;

    /** Free-form dry-run evidence object. Must not be empty. */
    @JsonProperty("dry_run")
    public Map<String, Object> dryRun;

    /** [0,1] estimated impact. Must be a reasonable estimate. */
    @JsonProperty("impact_score")
    public double impactScore;

    /** Human-readable rationale for the change. */
    @JsonProperty("rationale")
    public String rationale;

    /**
     * Optional evidence for prompt changes — e.g. {"validation_run_id": "val_00003"}.
     * Null for non-prompt changes. Required by the approver for prompt version changes.
     */
    @JsonProperty("evidence")
    public Map<String, Object> evidence;

    /** Hours until this proposal expires if not decided. Capped at policy TTL. */
    @JsonProperty("expires_in_hours")
    public Integer expiresInHours;
}
