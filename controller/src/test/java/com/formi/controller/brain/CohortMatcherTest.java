package com.formi.controller.brain;

import com.formi.controller.db.ExposureStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for CohortMatcher — exercises the OR-within-feature / AND-across-features rules.
 *
 * Uses in-memory SQLite for the ExposureStore aggregation replay test to verify
 * that ExposureStore counts are stable under a mid-campaign config change.
 */
class CohortMatcherTest {

    // ── CohortMatcher tests ────────────────────────────────────────────────

    private CohortMatcher.CohortDef makeCohort(String id, String feature, List<String> values) {
        CohortMatcher.CohortDef def = new CohortMatcher.CohortDef();
        def.cohortId = id;
        CohortMatcher.FeatureCondition cond = new CohortMatcher.FeatureCondition();
        cond.feature = feature;
        cond.values  = values;
        def.conditions = List.of(cond);
        return def;
    }

    private CohortMatcher.CohortDef makeCohortMultiFeature(String id,
                                                            String f1, List<String> v1,
                                                            String f2, List<String> v2) {
        CohortMatcher.CohortDef def = new CohortMatcher.CohortDef();
        def.cohortId = id;
        CohortMatcher.FeatureCondition c1 = new CohortMatcher.FeatureCondition();
        c1.feature = f1; c1.values = v1;
        CohortMatcher.FeatureCondition c2 = new CohortMatcher.FeatureCondition();
        c2.feature = f2; c2.values = v2;
        def.conditions = List.of(c1, c2);
        return def;
    }

    /**
     * Basic single-feature match: lang ∈ {hi, mr} → cohort "hindi_mr".
     */
    @Test
    void singleFeature_orMatch() {
        var cohorts = List.of(
                makeCohort("hindi_mr", "lang", List.of("hi", "mr")),
                makeCohort("english",  "lang", List.of("en"))
        );
        assertEquals("hindi_mr", CohortMatcher.match(java.util.Map.of("lang","hi"), cohorts));
        assertEquals("hindi_mr", CohortMatcher.match(java.util.Map.of("lang","mr"), cohorts));
        assertEquals("english",  CohortMatcher.match(java.util.Map.of("lang","en"), cohorts));
    }

    /**
     * No matching cohort → returns "default".
     */
    @Test
    void noMatch_returnsDefault() {
        var cohorts = List.of(makeCohort("hindi", "lang", List.of("hi")));
        assertEquals("default", CohortMatcher.match(java.util.Map.of("lang","ta"), cohorts));
    }

    /**
     * AND across features: must match BOTH lang=hi AND city_tier=1.
     */
    @Test
    void multiFeature_andMatch() {
        var cohorts = List.of(
                makeCohortMultiFeature("high_value", "lang", List.of("hi"), "city_tier", List.of("1"))
        );
        // Both match → cohort
        assertEquals("high_value", CohortMatcher.match(
                java.util.Map.of("lang","hi","city_tier","1"), cohorts));
        // One doesn't match → default
        assertEquals("default", CohortMatcher.match(
                java.util.Map.of("lang","hi","city_tier","2"), cohorts));
        assertEquals("default", CohortMatcher.match(
                java.util.Map.of("lang","en","city_tier","1"), cohorts));
    }

    /**
     * First-match-wins: a lead matching both cohorts goes to the first one.
     */
    @Test
    void firstMatchWins() {
        var cohorts = List.of(
                makeCohort("tier1", "city_tier", List.of("1")),
                makeCohort("any",   "city_tier", List.of("1","2","3"))
        );
        assertEquals("tier1", CohortMatcher.match(java.util.Map.of("city_tier","1"), cohorts));
        assertEquals("any",   CohortMatcher.match(java.util.Map.of("city_tier","2"), cohorts));
    }

    /**
     * Empty cohort list → "default".
     */
    @Test
    void emptyList_returnsDefault() {
        assertEquals("default", CohortMatcher.match(java.util.Map.of("lang","hi"), List.of()));
    }

    // ── ExposureStore aggregation replay test ─────────────────────────────

    /**
     * Insert known call rows into an in-memory SQLite, then verify ExposureStore
     * aggregation returns the expected counts.
     *
     * Also verifies crash safety: a mid-campaign config_version change does NOT
     * corrupt the counts because we always aggregate by the realised variant on
     * the call row (welcome_message, voice, etc.), never by the current config.
     */
    @Test
    void exposureStore_aggregatesCorrectly_midCampaignConfigChangeDoesNotCorrupt() throws Exception {
        // ── Setup in-memory SQLite ──────────────────────────────────────────
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE calls (
                    event_id INTEGER PRIMARY KEY,
                    ts TEXT, workflow_id TEXT, campaign_id TEXT,
                    lead_id TEXT, person_id TEXT,
                    attempt_no INTEGER, config_version INTEGER, cohort_id TEXT,
                    welcome_message TEXT, voice TEXT, caller_id TEXT, prompt_version TEXT,
                    connected INTEGER, engaged INTEGER, disposition TEXT,
                    hallucination_flag INTEGER, flow_adherence REAL
                )
            """);
        }

        // Insert 50 calls: voice=A (connected=30), 50 calls: voice=B (connected=20)
        // Simulate a config change at event 50: config_version changes from 1 → 2.
        // The ExposureStore should still report voice=A: 30 connected, voice=B: 20 connected
        // because it aggregates by the CALL's voice field, not by config_version.
        try (var ps = conn.prepareStatement("""
                INSERT INTO calls
                    (event_id,ts,workflow_id,campaign_id,lead_id,person_id,
                     attempt_no,config_version,cohort_id,
                     welcome_message,voice,caller_id,prompt_version,
                     connected,engaged,disposition,hallucination_flag,flow_adherence)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (int i = 1; i <= 100; i++) {
                String voice     = i <= 50 ? "A" : "B";
                int    connected = (i <= 50) ? (i <= 30 ? 1 : 0) : (i <= 70 ? 1 : 0); // A:30, B:20
                int    cfgVer    = i <= 50 ? 1 : 2; // config change at mid-campaign
                ps.setLong(1,   i);
                ps.setString(2, "2027-01-05T10:00:00+05:30");
                ps.setString(3, "nw_renewal");
                ps.setString(4, "camp1");
                ps.setString(5, "lead" + i);
                ps.setString(6, "person" + i);
                ps.setInt(7,    1);
                ps.setInt(8,    cfgVer);
                ps.setString(9, "default");
                ps.setString(10,"msg1");
                ps.setString(11, voice);
                ps.setString(12,"cid1");
                ps.setString(13,"pv1");
                ps.setInt(14,   connected);
                ps.setInt(15,   0);
                ps.setString(16,"no_answer");
                ps.setNull(17, java.sql.Types.INTEGER);
                ps.setNull(18, java.sql.Types.REAL);
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // ── Query via ExposureStore ─────────────────────────────────────────
        ExposureStore store = new ExposureStore(conn);
        List<ExposureStore.ExposureRow> rows =
                store.queryByDimension("nw_renewal", "voice", null, null);

        // Find rows for voice A and B
        ExposureStore.ExposureRow rowA = rows.stream()
                .filter(r -> "A".equals(r.value)).findFirst().orElse(null);
        ExposureStore.ExposureRow rowB = rows.stream()
                .filter(r -> "B".equals(r.value)).findFirst().orElse(null);

        assertNotNull(rowA, "Should have rows for voice=A");
        assertNotNull(rowB, "Should have rows for voice=B");

        // voice=A: 50 dials, 30 connected
        assertEquals(50, rowA.dials,     "voice=A should have 50 dials");
        assertEquals(30, rowA.connected, "voice=A should have 30 connected");

        // voice=B: 50 dials, 20 connected (the config change at event 50 must NOT affect this)
        assertEquals(50, rowB.dials,     "voice=B should have 50 dials");
        assertEquals(20, rowB.connected, "voice=B: mid-campaign config change must not corrupt count");

        conn.close();
    }
}
