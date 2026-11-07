package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Response from GET /workflows/{id}/config.
 *
 * The config itself is a free-form nested JSON object (cohorts, allocations,
 * schedule, prompt_version). We keep it as a raw JsonNode for two reasons:
 *   1. We store it verbatim in the DB and round-trip it back to the API on apply.
 *   2. The schema is complex and partially controlled by the business; mapping
 *      every sub-field would be fragile and over-constraining.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigVersion {
    /** Monotonically increasing version number. Used as `base_version` in proposals. */
    public int version;
    /** Full config JSON — treat as opaque until Day 2 experiments need to mutate it. */
    public JsonNode config;
}
