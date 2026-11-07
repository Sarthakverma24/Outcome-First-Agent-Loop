package com.formi.controller.client;

/**
 * 409 finished — the scenario has ended; no more advances are possible.
 * The main loop catches this and exits cleanly.
 */
public class FinishedException extends ApiException {
    public FinishedException(String body) {
        super(409, "finished", body);
    }
}
