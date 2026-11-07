package com.formi.controller.client;

/**
 * 409 duplicate_proposal_id — we already submitted a proposal with this ID.
 * Caller should look up the existing proposal and adopt its approval_id.
 */
public class DuplicateProposalException extends ApiException {
    public DuplicateProposalException(String body) {
        super(409, "duplicate_proposal_id", body);
    }
}
