package com.formi.controller.stats;

/**
 * Manual implementation of the two-proportion z-test.
 *
 * We write this by hand (no Apache Commons Math, no statistics library) because:
 * 1. The assignment says no extra dependencies.
 * 2. Every line must be explainable in a live review.
 *
 * The test asks: "Is the pickup rate (or engagement rate) of arm B significantly
 * different from arm A?" We use the pooled proportion estimate because under the
 * null hypothesis H₀ (p_A = p_B) the pooled estimate has lower variance than
 * either individual estimate.
 *
 * Formula:
 *   p_pool = (x_A + x_B) / (n_A + n_B)
 *   SE     = sqrt( p_pool * (1 - p_pool) * (1/n_A + 1/n_B) )
 *   z      = (p_B - p_A) / SE
 *
 * p-value from normal CDF using the Abramowitz & Stegun 26.2.17 approximation
 * (maximum error ≤ 7.5×10⁻⁸ — well within our precision needs).
 *
 * References:
 *   - Fleiss, Levin, Paik (2003) "Statistical Methods for Rates and Proportions"
 *   - Abramowitz & Stegun (1972) Handbook of Mathematical Functions, §26.2.17
 */
public final class TwoProportionZTest {

    private TwoProportionZTest() {}

    // ── Result record ──────────────────────────────────────────────────────

    public record Result(
            double zScore,       // signed: positive means B > A
            double pValueTwoTailed, // two-tailed p-value
            double effectSize,   // p_B - p_A (absolute difference)
            double pA,           // observed rate for arm A
            double pB            // observed rate for arm B
    ) {
        /** True if the result is statistically significant at level alpha. */
        public boolean isSignificant(double alpha) {
            return pValueTwoTailed < alpha;
        }
    }

    // ── Core test ──────────────────────────────────────────────────────────

    /**
     * Run the two-proportion z-test.
     *
     * @param successA number of successes (connected / engaged) in arm A (control)
     * @param trialA   number of trials (dials / connected) in arm A
     * @param successB number of successes in arm B (challenger)
     * @param trialB   number of trials in arm B
     * @return a Result object; if either arm is empty, returns a degenerate result
     *         with z=0 and p=1 to prevent false positives on empty data
     */
    public static Result test(int successA, int trialA, int successB, int trialB) {
        if (trialA <= 0 || trialB <= 0) {
            // Guard: cannot compute a rate for an empty arm.
            return new Result(0.0, 1.0, 0.0, 0.0, 0.0);
        }

        double pA     = (double) successA / trialA;
        double pB     = (double) successB / trialB;
        double pPool  = (double) (successA + successB) / (trialA + trialB);
        double effect = pB - pA;

        // Standard error under H₀ (pooled)
        double se = Math.sqrt(pPool * (1.0 - pPool) * (1.0 / trialA + 1.0 / trialB));

        if (se == 0.0) {
            // All successes or all failures — degenerate case, no information.
            return new Result(0.0, 1.0, effect, pA, pB);
        }

        double z    = effect / se;
        double pVal = 2.0 * normalCdfUpperTail(Math.abs(z)); // two-tailed

        return new Result(z, pVal, effect, pA, pB);
    }

    // ── Normal CDF approximation ───────────────────────────────────────────

    /**
     * Upper-tail probability P(Z > |z|) for a standard normal.
     *
     * Uses the rational approximation from Abramowitz & Stegun, §26.2.17.
     * Coefficients p, a1-a5 are from Table 26.2.17 (maximum error 7.5×10⁻⁸).
     *
     * This approximation is used in textbooks and financial calculators worldwide;
     * it is well-understood and easy to verify with a lookup table.
     */
    public static double normalCdfUpperTail(double z) {
        // Handle extreme values so we never return exactly 0 or 1
        if (z < 0)   z = -z; // symmetry: P(Z > z) = P(Z > |z|) for z < 0
        if (z > 8.0) return 0.0; // effectively zero at this precision

        // A&S 26.2.17 coefficients
        final double p  =  0.2316419;
        final double a1 =  0.319381530;
        final double a2 = -0.356563782;
        final double a3 =  1.781477937;
        final double a4 = -1.821255978;
        final double a5 =  1.330274429;

        double t    = 1.0 / (1.0 + p * z);
        double poly = t * (a1 + t * (a2 + t * (a3 + t * (a4 + t * a5))));
        double phi  = standardNormalPdf(z); // φ(z)
        return phi * poly;
    }

    /** Standard normal PDF: φ(z) = e^(-z²/2) / √(2π). */
    private static double standardNormalPdf(double z) {
        return Math.exp(-0.5 * z * z) / Math.sqrt(2.0 * Math.PI);
    }
}
