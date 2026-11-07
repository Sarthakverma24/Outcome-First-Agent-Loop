package com.formi.controller.client;

/**
 * 409 stale_base_version — the config changed after we read it.
 * Caller must re-read the current config, rebuild the proposal, and re-propose.
 */
public class StaleBaseVersionException extends ApiException {
    public StaleBaseVersionException(String body) {
        super(409, "stale_base_version", body);
    }
}
