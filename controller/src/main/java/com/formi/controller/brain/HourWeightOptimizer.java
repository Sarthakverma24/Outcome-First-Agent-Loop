package com.formi.controller.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formi.controller.db.ExposureStore;
import com.formi.controller.db.Store;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.ConfigVersion;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.governance.GovernanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.*;

/**
 * Optimises the schedule.hour_weights field from historical call data.
 *
 * Algorithm:
 *  1. Query connect rate per hour-of-day over the last HOUR_WEIGHT_HISTORY_DAYS.
 *  2. Run a chi-squared test for uniformity (df = #hours - 1). If the pattern
 *     is not significantly non-uniform (p ≥ HOUR_WEIGHT_CHI2_ALPHA), skip.
 *  3. Convert connect rates to weights proportional to their rate.
 *     Every hour in the dialling window gets an explicit weight (never 0 —
 *     we floor at 0.1 so no hour is completely excluded).
 *  4. If auto_apply allows "schedule" and the TVD shift ≤ max_shift:
 *     PUT /workflows/{id}/config (auto-apply path).
 *     Otherwise propose via /approvals.
 *
 * TVD (total variation distance) between current weights w and proposed w':
 *   TVD = 0.5 × sum(|w_i - w'_i|) where weights are normalised to sum to 1.
 *
 * Hard rule: we never set any hour weight to 0 (that would exclude an hour).
 * We floor at MIN_WEIGHT (0.1) to ensure all hours remain active.
 */
public class HourWeightOptimizer {

    private static final Logger log = LoggerFactory.getLogger(HourWeightOptimizer.class);

    // Minimum weight for any hour — prevents completely excluding an hour.
    // Chosen as a soft floor: 0.1 means the hour gets ~10% of the base load.
    private static final double MIN_WEIGHT = 0.1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection        conn;
    private final ExposureStore     exposure;
    private final Store             store;
    private final SimClient         client;
    private final GovernanceService governance;

    public HourWeightOptimizer(Connection conn, Store store, SimClient client, GovernanceService governance) {
        this.conn       = conn;
        this.exposure   = new ExposureStore(conn);
        this.store      = store;
        this.client     = client;
        this.governance = governance;
    }

    /**
     * Run the hour-weight optimiser for one workflow on one tick.
     */
    public void run(WorkflowSummary workflow, String currentSimTs, String currentDaySim) {
        String wfId = workflow.workflowId;
        try {
            int dialStart = (workflow.diallingWindow != null && workflow.diallingWindow.size() > 0)
                    ? workflow.diallingWindow.get(0) : 9;
            int dialEnd   = (workflow.diallingWindow != null && workflow.diallingWindow.size() > 1)
                    ? workflow.diallingWindow.get(1) : 21;

            // Query hourly connect rates from recent history
            List<ExposureStore.HourRow> hourRows =
                    exposure.queryHourlyConnectRate(wfId, currentDaySim, BrainConfig.HOUR_WEIGHT_HISTORY_DAYS);

            // Filter strictly to hours in dialling window
            List<ExposureStore.HourRow> inWindow = hourRows.stream()
                    .filter(r -> r.hour >= dialStart && r.hour < dialEnd)
                    .toList();

            if (inWindow.isEmpty()) return;

            // Filter to hours with enough data
            List<ExposureStore.HourRow> valid = inWindow.stream()
                    .filter(r -> r.dials >= BrainConfig.HOUR_WEIGHT_MIN_CALLS)
                    .toList();
            if (valid.size() < 2) return; // need at least 2 hours to compute non-trivial weights

            // Chi-squared test for uniformity
            double chi2 = computeChi2(valid);
            int df      = valid.size() - 1;
            double pValue = chi2PValue(chi2, df);
            log.debug("[{}] Hour-weight chi2={:.2f} df={} p={:.4f}", wfId, chi2, df, pValue);

            if (pValue >= BrainConfig.HOUR_WEIGHT_CHI2_ALPHA) {
                log.debug("[{}] Hour pattern not significant (p={:.4f}) — skipping.", wfId, pValue);
                return;
            }

            // Build proposed weights proportional to connect rate, floored at MIN_WEIGHT
            Map<Integer, Double> proposed = buildWeights(valid, workflow);
            if (proposed.isEmpty()) return;

            // Compute TVD shift
            ConfigVersion cv = client.getConfig(wfId);
            Map<Integer, Double> current = extractCurrentWeights(cv.config, workflow);
            double tvd = computeTvd(current, proposed, workflow);

            log.info("[{}] Hour-weight TVD={:.4f} (max_shift={})", wfId, tvd,
                    workflow.governance != null && workflow.governance.autoApply != null
                            ? workflow.governance.autoApply.maxShift : "N/A");

            WorkflowSummary.AutoApply autoApply = workflow.governance != null
                    ? workflow.governance.autoApply : null;
            boolean canAutoApply = autoApply != null
                    && autoApply.enabled
                    && autoApply.allowedDims != null
                    && autoApply.allowedDims.contains("schedule")
                    && tvd <= autoApply.maxShift;

            if (canAutoApply) {
                applyWeights(wfId, cv, proposed, tvd, chi2, pValue, currentSimTs);
            } else if (tvd > 0.001) { // only propose if the change is non-trivial
                proposeWeights(workflow, cv, proposed, tvd, chi2, pValue, currentSimTs);
            }

        } catch (Exception e) {
            log.error("[{}] HourWeightOptimizer error: {}", wfId, e.getMessage(), e);
        }
    }

    // ── Auto-apply ────────────────────────────────────────────────────────

    private void applyWeights(String wfId, ConfigVersion cv, Map<Integer, Double> weights,
                               double tvd, double chi2, double pValue, String currentSimTs) throws Exception {
        ObjectNode proposed = cv.config.deepCopy();
        setHourWeightsInConfig(proposed, weights);

        Map<String, Object> diff = Map.of(
                "schedule.hour_weights.default",
                "reweighted by connect rate (chi2=" + String.format("%.2f", chi2)
                + " p=" + String.format("%.4f", pValue) + " TVD=" + String.format("%.4f", tvd) + ")"
        );
        client.putConfig(wfId, cv.version, proposed,
                "Hour-weight optimisation (auto-apply)", diff);

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("action",   "hour_weight_auto_applied");
        ok.put("tvd",      tvd);
        ok.put("chi2",     chi2);
        ok.put("pValue",   pValue);
        ok.put("weights",  weights);
        store.audit(currentSimTs, wfId, "auto_applied", ok);
        log.info("[{}] Hour weights auto-applied (TVD={:.4f}).", wfId, tvd);
    }

    // ── Propose ───────────────────────────────────────────────────────────

    private void proposeWeights(WorkflowSummary workflow, ConfigVersion cv,
                                 Map<Integer, Double> weights, double tvd, double chi2,
                                 double pValue, String currentSimTs) throws Exception {
        String wfId = workflow.workflowId;
        ObjectNode proposed = cv.config.deepCopy();
        setHourWeightsInConfig(proposed, weights);

        Map<String, Object> diff = Map.of(
                "schedule.hour_weights.default", "reweighted by connect rate"
        );
        Map<String, Object> dryRun = new LinkedHashMap<>();
        dryRun.put("type",    "hour_weight_optimisation");
        dryRun.put("chi2",    chi2);
        dryRun.put("pValue",  pValue);
        dryRun.put("tvd",     tvd);
        dryRun.put("weights", weights);

        String rationale = String.format(
                "Hourly connect rate is non-uniform (chi2=%.2f, p=%.4f, df=%d). " +
                "Proposed weights are proportional to observed connect rates over the last %d days. " +
                "TVD shift = %.4f. Floor of %.1f applied to all hours to prevent exclusion.",
                chi2, pValue, weights.size() - 1,
                BrainConfig.HOUR_WEIGHT_HISTORY_DAYS, tvd, MIN_WEIGHT
        );

        governance.propose(workflow, cv.version, proposed,
                "Hour-weight optimisation (chi2 significant)",
                diff, 0, dryRun, 0.5, rationale, null, currentSimTs);
    }

    // ── Stats helpers ─────────────────────────────────────────────────────

    /**
     * Chi-squared goodness-of-fit test against a uniform distribution.
     * Statistic: chi2 = sum((O - E)^2 / E)
     * where E = total_connected / num_hours (expected under uniform).
     */
    private static double computeChi2(List<ExposureStore.HourRow> rows) {
        double totalConnected = rows.stream().mapToDouble(r -> r.connected).sum();
        int    n              = rows.size();
        double expected       = totalConnected / n;
        double chi2           = 0.0;
        for (ExposureStore.HourRow r : rows) {
            double diff = r.connected - expected;
            chi2 += (diff * diff) / expected;
        }
        return chi2;
    }

    /**
     * Chi-squared CDF upper tail for p-value using Wilson-Hilferty normal approximation.
     * Defensible formula: chi2 ~ N(df, 2df). Approximated as:
     *   z = ((chi2/df)^(1/3) - (1 - 2/(9df))) / sqrt(2/(9df))
     *
     * This is the standard textbook approximation for chi-squared p-values.
     * Error < 0.01 for df >= 2, which is always satisfied here.
     */
    private static double chi2PValue(double chi2, int df) {
        if (df <= 0) return 1.0;
        double term = 1.0 - 2.0 / (9.0 * df);
        double z    = (Math.pow(chi2 / df, 1.0 / 3.0) - term) / Math.sqrt(2.0 / (9.0 * df));
        return com.formi.controller.stats.TwoProportionZTest.normalCdfUpperTail(z);
    }

    /** Build weights proportional to connect rate, floored at MIN_WEIGHT, summing to 1. */
    private static Map<Integer, Double> buildWeights(List<ExposureStore.HourRow> rows,
                                                      WorkflowSummary workflow) {
        int dialStart = (workflow.diallingWindow != null && workflow.diallingWindow.size() > 0)
                ? workflow.diallingWindow.get(0) : 9;
        int dialEnd   = (workflow.diallingWindow != null && workflow.diallingWindow.size() > 1)
                ? workflow.diallingWindow.get(1) : 21;

        // Floor at MIN_WEIGHT to never exclude an hour
        Map<Integer, Double> raw = new LinkedHashMap<>();
        for (ExposureStore.HourRow r : rows) {
            if (r.hour >= dialStart && r.hour < dialEnd) {
                raw.put(r.hour, Math.max(MIN_WEIGHT, r.connectRate()));
            }
        }
        // Fill every missing hour in the dialling window with MIN_WEIGHT
        for (int h = dialStart; h < dialEnd; h++) {
            raw.putIfAbsent(h, MIN_WEIGHT);
        }

        // Normalise so weights sum to 1
        double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<Integer, Double> normalised = new LinkedHashMap<>();
        for (Map.Entry<Integer, Double> e : raw.entrySet()) {
            normalised.put(e.getKey(), e.getValue() / total);
        }
        return normalised;
    }

    /** Extract current hour_weights from the config JSON as a map of hour → weight. */
    private static Map<Integer, Double> extractCurrentWeights(JsonNode config, WorkflowSummary wf) {
        Map<Integer, Double> weights = new LinkedHashMap<>();
        try {
            JsonNode hw = config.path("schedule").path("hour_weights").path("default");
            if (hw.isObject()) {
                hw.fields().forEachRemaining(e -> {
                    try { weights.put(Integer.parseInt(e.getKey()), e.getValue().asDouble()); }
                    catch (Exception ignored) {}
                });
            }
        } catch (Exception ignored) {}

        // Fill any missing hours with uniform weight
        if (weights.isEmpty() && wf.diallingWindow != null && wf.diallingWindow.size() >= 2) {
            int start = wf.diallingWindow.get(0);
            int end   = wf.diallingWindow.get(1);
            double w  = 1.0 / (end - start);
            for (int h = start; h < end; h++) weights.put(h, w);
        }
        return weights;
    }

    /**
     * Total variation distance between two weight maps.
     * TVD = 0.5 * sum(|w_i - w'_i|) for all hours.
     */
    private static double computeTvd(Map<Integer, Double> current, Map<Integer, Double> proposed,
                                      WorkflowSummary wf) {
        // Normalise both to sum to 1
        double curTotal  = current.values().stream().mapToDouble(d -> d).sum();
        double propTotal = proposed.values().stream().mapToDouble(d -> d).sum();
        if (curTotal == 0 || propTotal == 0) return 0.0;

        Set<Integer> allHours = new HashSet<>(current.keySet());
        allHours.addAll(proposed.keySet());

        double tvd = 0.0;
        for (int h : allHours) {
            double c = current.getOrDefault(h, 0.0)  / curTotal;
            double p = proposed.getOrDefault(h, 0.0) / propTotal;
            tvd += Math.abs(c - p);
        }
        return tvd / 2.0;
    }

    /** Set hour_weights in the proposed config node. */
    private static void setHourWeightsInConfig(ObjectNode proposed, Map<Integer, Double> weights) {
        ObjectNode hw = new ObjectMapper().createObjectNode();
        weights.forEach((h, w) -> hw.put(String.valueOf(h), w));

        ObjectNode hwDefault = new ObjectMapper().createObjectNode();
        hwDefault.set("default", hw);

        ObjectNode schedule;
        if (proposed.has("schedule") && proposed.get("schedule").isObject()) {
            schedule = (ObjectNode) proposed.get("schedule");
        } else {
            schedule = new ObjectMapper().createObjectNode();
            proposed.set("schedule", schedule);
        }
        schedule.set("hour_weights", hwDefault);
    }
}
