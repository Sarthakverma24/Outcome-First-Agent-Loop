package com.formi.controller.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads aggregated exposure data from the {@code calls} table.
 *
 * "Realised exposure" means we always use the variant that was actually delivered
 * on a call (the values stored in welcome_message, voice, caller_id, prompt_version
 * on the call row), never the intended allocation from the config at the time.
 * This is the correct grain for causal inference: we learn from what leads heard,
 * not from what the system intended to send.
 *
 * Design: pure query object — no mutations, no business logic. The experiment engine
 * and detectors call these methods and interpret the results.
 */
public class ExposureStore {

    private final Connection conn;

    public ExposureStore(Connection conn) {
        this.conn = conn;
    }

    // ── Data structures ────────────────────────────────────────────────────

    /**
     * One aggregated row: how a specific arm performed within one cohort and
     * attempt_no slice on a given simulated day.
     *
     * Stratifying by attempt_no is essential: leads that answer on attempt 1 are
     * the full pool; leads that only appear on attempt 2 are those who didn't engage
     * on attempt 1 — a structurally different population. Mixing attempts without
     * stratification would bias the comparison.
     */
    public static class ExposureRow {
        public final String workflowId;
        public final String cohortId;
        public final String dimension;   // "voice", "welcome_message", "caller_id", "prompt_version"
        public final String value;       // the specific variant value
        public final int    attemptNo;
        public final String daySim;      // date portion of ts, e.g. "2027-01-05"
        public final int    dials;       // total calls for this arm/attempt/day
        public final int    connected;
        public final int    engaged;
        public final int    hallucinations;    // calls with hallucination_flag=1 (of connected)
        public final double flowAdherenceSum;  // sum of flow_adherence (of connected)

        public ExposureRow(String workflowId, String cohortId, String dimension, String value,
                           int attemptNo, String daySim,
                           int dials, int connected, int engaged,
                           int hallucinations, double flowAdherenceSum) {
            this.workflowId       = workflowId;
            this.cohortId         = cohortId;
            this.dimension        = dimension;
            this.value            = value;
            this.attemptNo        = attemptNo;
            this.daySim           = daySim;
            this.dials            = dials;
            this.connected        = connected;
            this.engaged          = engaged;
            this.hallucinations   = hallucinations;
            this.flowAdherenceSum = flowAdherenceSum;
        }

        /** Pickup rate = connected / dials (returns 0 if dials == 0). */
        public double pickupRate()    { return dials == 0 ? 0.0 : (double) connected / dials; }
        /** Engagement rate = engaged / connected (returns 0 if connected == 0). */
        public double engagementRate(){ return connected == 0 ? 0.0 : (double) engaged / connected; }
        /** Hallucination rate per connected call. */
        public double hallucinationRate() { return connected == 0 ? 0.0 : (double) hallucinations / connected; }
    }

    // ── Queries ────────────────────────────────────────────────────────────

    /**
     * Returns attempt-stratified daily exposure for a given dimension across all
     * cohorts and all variants, optionally filtered to a time range.
     *
     * We issue one query per dimension so the caller can group and pair arms cleanly.
     *
     * @param workflowId  the workflow to aggregate over
     * @param dimension   "voice", "welcome_message", "caller_id", or "prompt_version"
     * @param fromDaySim  inclusive lower bound on date(ts), e.g. "2027-01-01". Null = no bound.
     * @param toDaySim    inclusive upper bound. Null = no bound.
     */
    public List<ExposureRow> queryByDimension(
            String workflowId, String dimension, String fromDaySim, String toDaySim
    ) throws SQLException {
        // The column name in the calls table IS the dimension name, except
        // "welcome_message" maps to "welcome_message". All four are literal column names.
        // We use a parameterized column name via a switch to avoid SQL injection.
        String col = dimensionColumn(dimension);

        // Build the query using string concatenation (col is validated by dimensionColumn
        // via a switch — not user input, so no SQL injection risk).
        String baseSql =
                "SELECT workflow_id, cohort_id, ? AS dimension, " + col + " AS value, " +
                "attempt_no, date(ts) AS day_sim, COUNT(*) AS dials, " +
                "SUM(connected) AS connected, SUM(engaged) AS engaged, " +
                "SUM(CASE WHEN hallucination_flag = 1 AND connected = 1 THEN 1 ELSE 0 END) AS hallucinations, " +
                "SUM(CASE WHEN connected = 1 THEN COALESCE(flow_adherence, 0) ELSE 0 END) AS flow_adherence_sum " +
                "FROM calls WHERE workflow_id = ? AND " + col + " IS NOT NULL";

        StringBuilder sb = new StringBuilder(baseSql);

        if (fromDaySim != null) sb.append(" AND date(ts) >= ?");
        if (toDaySim   != null) sb.append(" AND date(ts) <= ?");

        sb.append(" GROUP BY workflow_id, cohort_id, " + col + ", attempt_no, date(ts)");

        try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
            int idx = 1;
            ps.setString(idx++, dimension);
            ps.setString(idx++, workflowId);
            if (fromDaySim != null) ps.setString(idx++, fromDaySim);
            if (toDaySim   != null) ps.setString(idx++, toDaySim);

            List<ExposureRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ExposureRow(
                            rs.getString("workflow_id"),
                            rs.getString("cohort_id"),
                            rs.getString("dimension"),
                            rs.getString("value"),
                            rs.getInt("attempt_no"),
                            rs.getString("day_sim"),
                            rs.getInt("dials"),
                            rs.getInt("connected"),
                            rs.getInt("engaged"),
                            rs.getInt("hallucinations"),
                            rs.getDouble("flow_adherence_sum")
                    ));
                }
            }
            return rows;
        }
    }

    /**
     * Returns per-caller_id pickup stats for a workflow over the last {@code windowDays}
     * simulated days (measured back from {@code currentDaySim}).
     *
     * Used by the caller-ID health detector.
     */
    public List<CallerIdRow> queryCallerIdStats(
            String workflowId, String currentDaySim, int windowDays
    ) throws SQLException {
        String sql = """
                SELECT
                    caller_id,
                    COUNT(*)       AS dials,
                    SUM(connected) AS pickups
                FROM calls
                WHERE workflow_id = ?
                  AND date(ts) > date(?, '-' || ? || ' days')
                  AND date(ts) <= ?
                  AND caller_id IS NOT NULL
                GROUP BY caller_id
                HAVING COUNT(*) > 0
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, currentDaySim);
            ps.setInt(3, windowDays);
            ps.setString(4, currentDaySim);
            List<CallerIdRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CallerIdRow(
                            rs.getString("caller_id"),
                            rs.getInt("dials"),
                            rs.getInt("pickups")
                    ));
                }
            }
            return rows;
        }
    }

    /** Pickup stats for one caller_id over a rolling window. */
    public static class CallerIdRow {
        public final String callerId;
        public final int    dials;
        public final int    pickups;
        public CallerIdRow(String callerId, int dials, int pickups) {
            this.callerId = callerId;
            this.dials    = dials;
            this.pickups  = pickups;
        }
        public double pickupRate() { return dials == 0 ? 0.0 : (double) pickups / dials; }
    }

    /**
     * Returns hallucination rate per prompt_version for a workflow over a date range.
     * Only connected calls contribute (hallucination_flag is only meaningful when connected).
     */
    public List<HalluRow> queryHallucinationByPrompt(
            String workflowId, String fromDaySim, String toDaySim
    ) throws SQLException {
        String sql = """
                SELECT
                    prompt_version,
                    SUM(connected)                                                AS connected,
                    SUM(CASE WHEN hallucination_flag = 1 AND connected=1 THEN 1 ELSE 0 END) AS hallu
                FROM calls
                WHERE workflow_id = ?
                  AND date(ts) >= ?
                  AND date(ts) <= ?
                  AND prompt_version IS NOT NULL
                GROUP BY prompt_version
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, fromDaySim);
            ps.setString(3, toDaySim);
            List<HalluRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HalluRow(
                            rs.getString("prompt_version"),
                            rs.getInt("connected"),
                            rs.getInt("hallu")
                    ));
                }
            }
            return rows;
        }
    }

    /** Hallucination stats for one prompt_version. */
    public static class HalluRow {
        public final String promptVersion;
        public final int    connected;
        public final int    halluCount;
        public HalluRow(String promptVersion, int connected, int halluCount) {
            this.promptVersion = promptVersion;
            this.connected     = connected;
            this.halluCount    = halluCount;
        }
        public double halluRate() { return connected == 0 ? 0.0 : (double) halluCount / connected; }
    }

    /**
     * Returns hourly connect rate for a workflow over the last {@code historyDays}.
     * Used by the hour-weight optimiser.
     */
    public List<HourRow> queryHourlyConnectRate(
            String workflowId, String currentDaySim, int historyDays
    ) throws SQLException {
        String sql = """
                SELECT
                    CAST(strftime('%H', ts) AS INTEGER) AS hour_of_day,
                    COUNT(*)       AS dials,
                    SUM(connected) AS connected
                FROM calls
                WHERE workflow_id = ?
                  AND date(ts) > date(?, '-' || ? || ' days')
                  AND date(ts) <= ?
                GROUP BY hour_of_day
                ORDER BY hour_of_day
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, workflowId);
            ps.setString(2, currentDaySim);
            ps.setInt(3, historyDays);
            ps.setString(4, currentDaySim);
            List<HourRow> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new HourRow(
                            rs.getInt("hour_of_day"),
                            rs.getInt("dials"),
                            rs.getInt("connected")
                    ));
                }
            }
            return rows;
        }
    }

    /** Connect-rate stats for one hour of day. */
    public static class HourRow {
        public final int hour;
        public final int dials;
        public final int connected;
        public HourRow(int hour, int dials, int connected) {
            this.hour      = hour;
            this.dials     = dials;
            this.connected = connected;
        }
        public double connectRate() { return dials == 0 ? 0.0 : (double) connected / dials; }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Map dimension name → actual column name in the calls table. */
    private static String dimensionColumn(String dimension) {
        return switch (dimension) {
            case "voice"           -> "voice";
            case "welcome_message" -> "welcome_message";
            case "caller_id"       -> "caller_id";
            case "prompt_version"  -> "prompt_version";
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        };
    }
}
