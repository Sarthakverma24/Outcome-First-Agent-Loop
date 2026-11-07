package com.formi.controller.client;

/**
 * 410 lapsed — the apply window for an approved proposal has passed.
 * The approval is gone; re-propose if the change is still desired.
 */
public class LapsedException extends ApiException {
    public LapsedException(String body) {
        super(410, "lapsed", body);
    }
}
