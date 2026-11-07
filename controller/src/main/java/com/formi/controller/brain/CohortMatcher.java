package com.formi.controller.brain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Assigns a lead to its cohort based on the workflow's cohort definitions.
 *
 * Matching rules (from SIMULATOR.md):
 *   - Within a single feature, the values form an OR group: the lead matches if
 *     its feature value equals ANY of the listed values.
 *   - Across features, the conditions are ANDed: the lead must match ALL features
 *     listed in the cohort definition.
 *   - Cohorts are evaluated in order; the first match wins.
 *   - If no cohort matches, the lead belongs to "default".
 *
 * Design: static method, no state. Called once per ingested call event (the ingester
 * already stores cohort_id on the call row). Also called by the experiment engine to
 * decide which experiment a call contributes to.
 */
public final class CohortMatcher {

    private CohortMatcher() {} // utility class

    // ── Data model (deserialized from workflow JSON) ───────────────────────

    /**
     * One feature condition inside a cohort definition.
     * Example JSON: {"feature": "lang", "values": ["hi", "mr"]}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FeatureCondition {
        @JsonProperty("feature") public String feature;   // e.g. "lang"
        @JsonProperty("values")  public List<String> values; // OR group
    }

    /**
     * One cohort definition from GET /workflows/{id}.
     * Example JSON: {"cohort_id": "hindi_tier1", "conditions": [...]}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CohortDef {
        @JsonProperty("cohort_id")   public String cohortId;
        @JsonProperty("conditions")  public List<FeatureCondition> conditions;
    }

    // ── Core matching logic ────────────────────────────────────────────────

    /**
     * Returns the first cohort_id from {@code cohorts} that the lead matches,
     * or {@code "default"} if none match.
     *
     * @param leadFeatures map of feature name → value for the lead
     *                     (e.g. {"lang":"hi", "city_tier":"1", "age_band":"25-35"})
     * @param cohorts      ordered list from the workflow definition (preserves order)
     */
    public static String match(Map<String, String> leadFeatures, List<CohortDef> cohorts) {
        if (cohorts == null || cohorts.isEmpty()) return "default";

        for (CohortDef cohort : cohorts) {
            if (cohort.conditions == null || cohort.conditions.isEmpty()) {
                // A cohort with no conditions is a catch-all — treat as default
                return cohort.cohortId;
            }

            boolean allMatch = true;
            for (FeatureCondition cond : cohort.conditions) {
                String leadValue = leadFeatures.getOrDefault(cond.feature, "");
                // OR within values: the lead's feature value must appear in the list
                boolean featureMatch = cond.values != null && cond.values.contains(leadValue);
                if (!featureMatch) {
                    allMatch = false;
                    break; // AND-short-circuit: no point checking remaining features
                }
            }

            if (allMatch) return cohort.cohortId;
        }

        return "default";
    }
}
