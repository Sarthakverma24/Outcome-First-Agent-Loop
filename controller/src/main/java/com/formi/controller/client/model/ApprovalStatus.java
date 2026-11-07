package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Full detail for one approval, returned by GET /approvals/{id}
 * and elements of GET /approvals?workflow_id=&status=.
 *
 * See ASSUMPTIONS.md §A2 for the assumed response shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalStatus {
    @JsonProperty("approval_id")
    public String approvalId;
    @JsonProperty("proposal_id")
    public String proposalId;
    @JsonProperty("workflow_id")
    public String workflowId;

    /**
     * State machine: pending → approved | rejected | expired
     *                approved → applied | lapsed
     */
    @JsonProperty("status")
    public String status;

    /** Reasons provided when status is rejected or expired. */
    @JsonProperty("reasons")
    public List<String> reasons;

    /**
     * ISO-8601 deadline: apply before this time or the approval lapses (410).
     * Null while still pending or if already decided negatively.
     * See ASSUMPTIONS.md §A2.
     */
    @JsonProperty("apply_by")
    public String applyBy;

    /** ISO-8601 timestamp when the approver made a decision. Null while pending. */
    @JsonProperty("decided_at")
    public String decidedAt;

    // ── Convenience predicates ────────────────────────────────────────────────

    public boolean isPending()   { return "pending".equals(status); }
    public boolean isApproved()  { return "approved".equals(status); }
    public boolean isTerminal()  {
        // terminal = no further action is possible from our side
        return switch (status == null ? "" : status) {
            case "rejected", "expired", "applied", "lapsed" -> true;
            default -> false;
        };
    }
}
