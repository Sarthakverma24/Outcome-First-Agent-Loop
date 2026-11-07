package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Response body from POST /approvals on success. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApprovalSubmitResult {
    /** The approval_id assigned by the simulator. Poll this with GET /approvals/{id}. */
    public String approvalId;
}
