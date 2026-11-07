package com.formi.controller.client;

/**
 * 402 — scenario credit budget exhausted.
 * LLM and validation calls cost credits. Caller must stop making credit-consuming
 * requests and log the event prominently.
 */
public class InsufficientCreditsException extends ApiException {
    public InsufficientCreditsException(String body) {
        super(402, "insufficient_credits", body);
    }
}
