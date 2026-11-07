package com.formi.controller.stats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TwoProportionZTest and HolmCorrection.
 *
 * All expected values are computed from first principles (or verified against
 * published tables) so that every assertion can be defended in a live review.
 */
class StatsTest {

    // ── TwoProportionZTest ─────────────────────────────────────────────────

    /**
     * Known case from Fleiss et al. §3.4 (modified to our exact numbers):
     * p1=0.50, n1=400, p2=0.56, n2=400
     *
     * Pooled proportion: p = (200+224)/(400+400) = 424/800 = 0.530
     * SE = sqrt(0.530 * 0.470 * (1/400 + 1/400)) = sqrt(0.2491 * 0.005) = sqrt(0.001246) ≈ 0.03529
     * z  = (0.56 - 0.50) / 0.03529 ≈ 1.700
     * two-tailed p ≈ 0.089 (matches normal table)
     *
     * We test that:
     *  - |z| is within 0.05 of the analytical value (approximation tolerance)
     *  - p-value is NOT significant at α=0.05 (expected: fail to reject H₀)
     *  - effect size sign is correct (p2 > p1 → positive z)
     */
    @Test
    void knownCase_p1half_p2highSixty() {
        int successA = 200, trialA = 400; // p=0.50
        int successB = 224, trialB = 400; // p=0.56

        TwoProportionZTest.Result r = TwoProportionZTest.test(successA, trialA, successB, trialB);

        // z ≈ 1.70
        assertEquals(1.70, r.zScore(), 0.05, "z-score should be ~1.70");
        // p ≈ 0.089 — NOT significant at 0.05
        assertTrue(r.pValueTwoTailed() > 0.05, "Should fail to reject at α=0.05, got p=" + r.pValueTwoTailed());
        // But the effect is in the right direction
        assertTrue(r.effectSize() > 0, "Effect should be positive (p2 > p1)");
        assertEquals(0.06, r.effectSize(), 0.001, "Effect size should be 0.06");
    }

    /**
     * Large sample, clearly significant case:
     * p1=0.40, n1=1000, p2=0.46, n2=1000
     * z ≈ 2.74 → p ≈ 0.006 → significant at 0.05
     */
    @Test
    void clearWinner_significantResult() {
        TwoProportionZTest.Result r = TwoProportionZTest.test(400, 1000, 460, 1000);
        assertTrue(r.isSignificant(0.05), "Should be significant at α=0.05");
        assertTrue(r.zScore() > 2.0, "z should be > 2.0, got " + r.zScore());
        assertTrue(r.effectSize() > 0.05, "Effect should be > 5pp");
    }

    /**
     * Degenerate: empty arm should return z=0, p=1 (never false positive).
     */
    @Test
    void emptyArm_returnsDegenerate() {
        TwoProportionZTest.Result r = TwoProportionZTest.test(0, 0, 100, 200);
        assertEquals(0.0, r.zScore(), 1e-9);
        assertEquals(1.0, r.pValueTwoTailed(), 1e-9);
    }

    /**
     * No difference: both arms have the same rate → z=0, p=1.
     */
    @Test
    void noDifference_zeroZ() {
        TwoProportionZTest.Result r = TwoProportionZTest.test(200, 400, 200, 400);
        assertEquals(0.0, r.zScore(), 1e-6);
        assertEquals(1.0, r.pValueTwoTailed(), 0.01); // pooled SE still 0 → degenerate
    }

    /**
     * Normal CDF upper tail check:
     * P(Z > 1.96) ≈ 0.025 (classic 95% CI boundary).
     * Our approximation should be within 0.001 of this.
     */
    @Test
    void normalCdf_z196() {
        double p = TwoProportionZTest.normalCdfUpperTail(1.96);
        assertEquals(0.025, p, 0.001, "P(Z>1.96) should be ~0.025");
    }

    @Test
    void normalCdf_z258() {
        double p = TwoProportionZTest.normalCdfUpperTail(2.576);
        assertEquals(0.005, p, 0.001, "P(Z>2.576) should be ~0.005");
    }

    // ── HolmCorrection ─────────────────────────────────────────────────────

    /**
     * Classic textbook Holm example with 3 p-values, α=0.05:
     *   p-values sorted: 0.01, 0.04, 0.09
     *   rank 1 threshold: 0.05/3 = 0.0167 → 0.01 < 0.0167 → REJECT
     *   rank 2 threshold: 0.05/2 = 0.025  → 0.04 > 0.025  → FAIL (stop)
     *   rank 3 threshold: 0.05/1 = 0.05   → 0.09 > 0.05   → FAIL (already stopped)
     *
     * Expected: only the first (p=0.01) is rejected.
     */
    @Test
    void holmThreePValues_onlySmallestRejected() {
        double[] pValues = { 0.04, 0.01, 0.09 }; // deliberately not sorted
        HolmCorrection.TestResult[] results = HolmCorrection.correct(pValues, 0.05);

        // Index 0 (p=0.04) → rank 2 → NOT rejected
        assertFalse(results[0].rejected(), "p=0.04 should not be rejected");
        // Index 1 (p=0.01) → rank 1 → REJECTED
        assertTrue(results[1].rejected(), "p=0.01 should be rejected");
        // Index 2 (p=0.09) → rank 3 → NOT rejected
        assertFalse(results[2].rejected(), "p=0.09 should not be rejected");
    }

    /**
     * When all p-values are well below α/m, all are rejected.
     */
    @Test
    void holmAllSignificant() {
        double[] pValues = { 0.001, 0.002, 0.003 };
        HolmCorrection.TestResult[] results = HolmCorrection.correct(pValues, 0.05);
        for (HolmCorrection.TestResult r : results) {
            assertTrue(r.rejected(), "All p-values below Holm threshold should be rejected");
        }
    }

    /**
     * When all p-values are above α, none are rejected.
     */
    @Test
    void holmNoneSignificant() {
        double[] pValues = { 0.1, 0.2, 0.3 };
        HolmCorrection.TestResult[] results = HolmCorrection.correct(pValues, 0.05);
        for (HolmCorrection.TestResult r : results) {
            assertFalse(r.rejected(), "No p-values should be rejected");
        }
    }

    /**
     * Single p-value: behaves as plain Bonferroni (threshold = α/1 = α).
     */
    @Test
    void holmSinglePValue() {
        HolmCorrection.TestResult[] sig    = HolmCorrection.correct(new double[]{0.03}, 0.05);
        HolmCorrection.TestResult[] notSig = HolmCorrection.correct(new double[]{0.07}, 0.05);
        assertTrue(sig[0].rejected());
        assertFalse(notSig[0].rejected());
    }
}
