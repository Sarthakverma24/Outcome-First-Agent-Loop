package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Response from GET /sim/state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SimState {
    /** Current simulated timestamp (ISO 8601). */
    public String ts;
    /** True once the scenario has ended (no more advances possible). */
    public boolean finished;
    /** Remaining scenario credits for LLM and validation calls. */
    public int creditsRemaining;
}
