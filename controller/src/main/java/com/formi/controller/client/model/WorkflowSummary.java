package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Full detail of one workflow, returned by GET /workflows and GET /workflows/{id}.
 *
 * We only map the fields we actually use. Unknown JSON fields are silently ignored
 * (@JsonIgnoreProperties) so the model stays stable when the simulator adds fields.
 *
 * Fields NOT mapped today (because Day 1 never changes them):
 *   dials_per_hour, cadence, daily_batch, mid_flight (stored as raw JSON in db).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowSummary {

    @JsonProperty("workflow_id")
    public String workflowId;           // "nw_renewal"

    @JsonProperty("tenant_id")
    public String tenantId;             // "northwind_life"

    @JsonProperty("options")
    public Options options;             // available variants

    @JsonProperty("governance")
    public Governance governance;       // approval / auto-apply policy

    @JsonProperty("objective")
    public Objective objective;         // primary KPI + guardrail

    @JsonProperty("dialling_window")
    public List<Integer> diallingWindow; // [start, end) hours IST

    // ── Nested types ──────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Options {
        @JsonProperty("voice")
        public List<String> voice;
        @JsonProperty("welcome_message")
        public List<String> welcomeMessage;
        @JsonProperty("caller_id")
        public List<String> callerId;
        @JsonProperty("prompt_version")
        public List<String> promptVersion;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Governance {
        /** [start, end) hours IST during which the approver is active. */
        @JsonProperty("approver_hours")
        public List<Integer> approverHours;

        /** Max proposal submissions per 24 simulated hours for this workflow. */
        @JsonProperty("max_requests_per_24h")
        public int maxRequestsPer24h;

        /** Hours until an un-decided proposal expires. */
        @JsonProperty("proposal_ttl_hours")
        public int proposalTtlHours;

        /** Hours after approval within which we must call /apply before it lapses. */
        @JsonProperty("apply_window_hours")
        public int applyWindowHours;

        /** Fraction of proposals rejected for subjective reasons (informational). */
        @JsonProperty("base_reject_rate")
        public double baseRejectRate;

        /** Min validation pass rate required for prompt changes. */
        @JsonProperty("min_validation_pass_rate")
        public double minValidationPassRate;

        /** Latency range the approver takes to respond, in minutes: [min, max]. */
        @JsonProperty("approver_latency_minutes")
        public List<Integer> approverLatencyMinutes;

        @JsonProperty("auto_apply")
        public AutoApply autoApply;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AutoApply {
        @JsonProperty("enabled")
        public boolean enabled;
        /** Dimensions that may be changed via PUT /workflows/{id}/config. */
        @JsonProperty("allowed_dims")
        public List<String> allowedDims;
        /** Max total-variation distance shift allowed per cohort per dimension. */
        @JsonProperty("max_shift")
        public double maxShift;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Objective {
        /** "pickup_rate" or "engagement_rate" */
        @JsonProperty("primary")
        public String primary;
        @JsonProperty("guardrail")
        public Guardrail guardrail;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Guardrail {
        /** Max allowed drop in pickup_rate (fractional). Null if not constrained. */
        @JsonProperty("pickup_rate_max_drop")
        public Double pickupRateMaxDrop;
        /** Max allowed drop in engagement_rate (fractional). Null if not constrained. */
        @JsonProperty("engagement_rate_max_drop")
        public Double engagementRateMaxDrop;
    }

    // Jackson requires setters or public fields for deserialization when there's
    // no @JsonCreator. We use public fields throughout the model layer for brevity —
    // these objects are internal DTO reads, never modified after construction.
    // The snake_case → camelCase mapping is handled by configuring the ObjectMapper
    // in SimClient with SNAKE_CASE naming strategy.
}
