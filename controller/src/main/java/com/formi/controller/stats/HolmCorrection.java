package com.formi.controller.stats;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Manual implementation of the Holm-Bonferroni step-down multiple comparison correction.
 *
 * When we test m hypotheses simultaneously (e.g. 2 challengers vs control), the chance
 * of at least one false positive inflates to roughly m × α. Holm corrects this while
 * being less conservative than the Bonferroni method (which uses α/m for all tests).
 *
 * Algorithm (Holm 1979):
 *   1. Sort the m p-values in ascending order: p_(1) ≤ p_(2) ≤ … ≤ p_(m).
 *   2. For rank k (1-indexed), the corrected threshold is α / (m - k + 1).
 *   3. Starting from k=1, reject H₀ if p_(k) < threshold.
 *   4. As soon as p_(k) ≥ threshold, stop — keep H₀ for all remaining tests.
 *      (The step-down property: once we fail to reject, all subsequent also fail.)
 *
 * This controls the familywise error rate (FWER) at α without being as strict
 * as Bonferroni, giving us more power when one challenger is clearly better.
 *
 * Reference: Holm, S. (1979). "A simple sequentially rejective multiple test procedure."
 *   Scandinavian Journal of Statistics, 6(2), 65–70.
 */
public final class HolmCorrection {

    private HolmCorrection() {}

    // ── Result record ──────────────────────────────────────────────────────

    /**
     * Result for one input test.
     * {@code rejected} is true when H₀ is rejected at the given alpha after Holm.
     */
    public record TestResult(
            int    originalIndex,  // position in the input array (for caller to match back)
            double pValue,
            double holmThreshold,  // α / (m - k + 1) for this test's rank k
            boolean rejected
    ) {}

    // ── Core correction ────────────────────────────────────────────────────

    /**
     * Apply Holm-Bonferroni correction to an array of p-values.
     *
     * @param pValues the raw p-values, in any order
     * @param alpha   the familywise significance level (usually 0.05)
     * @return one {@link TestResult} per input p-value, in the SAME order as input.
     *         Use {@code originalIndex} to match results back to your hypotheses.
     */
    public static TestResult[] correct(double[] pValues, double alpha) {
        int m = pValues.length;
        if (m == 0) return new TestResult[0];

        // Build an array of (original index, p-value) pairs so we can sort, then
        // restore original ordering in the output.
        Integer[] indices = new Integer[m];
        for (int i = 0; i < m; i++) indices[i] = i;

        // Sort indices by ascending p-value (the step-down direction)
        Arrays.sort(indices, Comparator.comparingDouble(i -> pValues[i]));

        TestResult[] results = new TestResult[m];
        boolean stopRejecting = false;

        for (int rank = 0; rank < m; rank++) {
            int origIdx = indices[rank];
            double p    = pValues[origIdx];

            // Holm threshold for rank k = rank+1 (1-indexed): α / (m - k + 1)
            double threshold = alpha / (double) (m - rank);

            // Step-down: once we fail to reject, all subsequent tests also fail
            if (!stopRejecting && p < threshold) {
                results[origIdx] = new TestResult(origIdx, p, threshold, true);
            } else {
                stopRejecting = true;
                results[origIdx] = new TestResult(origIdx, p, threshold, false);
            }
        }

        return results;
    }
}
