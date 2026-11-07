package com.formi.controller.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All database read/write operations for the controller.
 *
 * Design decisions:
 *
 *  1. Plain JDBC with PreparedStatements — no ORM. Every query is explicit and
 *     easy to audit. Performance is fine: SQLite in WAL mode handles thousands
 *     of inserts per second, more than enough for 850k events/day.
 *
 *  2. No connection pool — the main loop runs on one thread and owns one
 *     connection. Transaction management (setAutoCommit/commit/rollback) is
 *     handled by the caller (Ingester) so the Store is transaction-agnostic.
 *
 *  3. Prepared statements are created lazily per-call. For repeated hot paths
 *     (insertCall, insertLead) the JVM will JIT-compile the JDBC call anyway;
 *     caching PS objects adds complexity without measurable gain at our scale.
 */
public class Store {

    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private final Connection conn;
    private final ObjectMapper mapper = new ObjectMapper();

    public Store(Connection conn) {
        this.conn = conn;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // kv_state — cursor, flags, run metadata
    // ══════════════════════════════════════════════════════════════════════════

    public String getKv(String key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT value FROM kv_state WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setKv(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO kv_state(key,value) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    /** Last fully-ingested chunk number. Returns -1 if nothing ingested yet. */
    public int getEventCursor() throws SQLException {
        String v = getKv("event_cursor");
        return v == null ? -1 : Integer.parseInt(v);
    }

    /** Persist the cursor — called inside the ingestion transaction. */
    public void setEventCursor(int chunkNo) throws SQLException {
        setKv("event_cursor", String.valueOf(chunkNo));
    }

    public boolean isHistoryBootstrapped() throws SQLException {
        return "true".equals(getKv("history_bootstrapped"));
    }

    public void markHistoryBootstrapped() throws SQLException {
        setKv("history_bootstrapped", "true");
    }

    /** Current simulated timestamp (from the last advance). */
    public String getSimTs() throws SQLException {
        return getKv("sim_ts");
    }

    public void setSimTs(String ts) throws SQLException {
        setKv("sim_ts", ts);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Leads
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * INSERT OR IGNORE — safe to call twice for the same lead_id.
     * On a replay, duplicate rows are silently dropped.
     */
    public void insertLead(
            String leadId, String personId, String workflowId, String campaignId,
            String lang, String cityTier, String ageBand, String valueBand
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO leads
                    (lead_id,person_id,workflow_id,campaign_id,lang,city_tier,age_band,value_band)
                VALUES (?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, leadId);
            ps.setString(2, personId);
            ps.setString(3, workflowId);
            ps.setString(4, campaignId);
            ps.setString(5, lang);
            ps.setString(6, cityTier);
            ps.setString(7, ageBand);
            ps.setString(8, valueBand);
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Calls
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * INSERT OR IGNORE by event_id — the primary idempotency guard.
     * If we re-process a chunk after a crash, duplicate events are silently dropped.
     */
    public void insertCall(
            long eventId, String ts, String workflowId, String campaignId,
            String leadId, String personId, Integer attemptNo, Integer configVersion,
            String cohortId, String welcomeMessage, String voice, String callerId,
            String promptVersion, boolean connected, boolean engaged,
            String disposition, Boolean hallucinationFlag, Double flowAdherence
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO calls (
                    event_id, ts, workflow_id, campaign_id, lead_id, person_id,
                    attempt_no, config_version, cohort_id,
                    welcome_message, voice, caller_id, prompt_version,
                    connected, engaged, disposition,
                    hallucination_flag, flow_adherence
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setLong(1, eventId);
            ps.setString(2, ts);
            ps.setString(3, workflowId);
            ps.setString(4, campaignId);
            ps.setString(5, leadId);
            ps.setString(6, personId);
            setNullableInt(ps, 7, attemptNo);
            setNullableInt(ps, 8, configVersion);
            ps.setString(9, cohortId);
            ps.setString(10, welcomeMessage);
            ps.setString(11, voice);
            ps.setString(12, callerId);
            ps.setString(13, promptVersion);
            ps.setInt(14, connected ? 1 : 0);
            ps.setInt(15, engaged ? 1 : 0);
            ps.setString(16, disposition);
            setNullableBool(ps, 17, hallucinationFlag);
            setNullableDouble(ps, 18, flowAdherence);
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Config versions
    // ══════════════════════════════════════════════════════════════════════════

    public void insertConfigVersion(
            String workflowId, int version, String configJson,
            String actor, String ts, String changedDimsJson
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO config_versions
                    (workflow_id, version, config_json, actor, ts, changed_dims)
                VALUES (?,?,?,?,?,?)
                """)) {
            ps.setString(1, workflowId);
            ps.setInt(2, version);
            ps.setString(3, configJson);
            ps.setString(4, actor);
            ps.setString(5, ts);
            ps.setString(6, changedDimsJson);
            ps.executeUpdate();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Approvals
    // ══════════════════════════════════════════════════════════════════════════

    public void insertApproval(
            String proposalId, String approvalId, String workflowId,
            int baseVersion, String status, String proposedConfigJson,
            String summary, String rationale, String createdTs
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO approvals
                    (proposal_id, approval_id, workflow_id, base_version, status,
                     proposed_config_json, summary, rationale, created_ts)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, proposalId);
            ps.setString(2, approvalId);
            ps.setString(3, workflowId);
            ps.setInt(4, baseVersion);
            ps.setString(5, status);
            ps.setString(6, proposedConfigJson);
            ps.setString(7, summary);
            ps.setString(8, rationale);
            ps.setString(9, createdTs);
            ps.executeUpdate();
        }
    }

    public void updateApprovalStatus(
            String proposalId, String status, String decidedTs, String reasonsJson
    ) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE approvals
                SET status=?, decided_ts=?, reasons_json=?
                WHERE proposal_id=?
                """)) {
            ps.setString(1, status);
            ps.setString(2, decidedTs);
            ps.setString(3, reasonsJson);
            ps.setString(4, proposalId);
            ps.executeUpdate();
        }
    }

    public void markApprovalApplied(String proposalId, String wallTs) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE approvals SET status='applied', applied_ts=?
                WHERE proposal_id=?
                """)) {
            ps.setString(1, wallTs);
            ps.setString(2, proposalId);
            ps.executeUpdate();
        }
    }

    /** All proposals not yet in a terminal state — used by the reconciler on startup. */
    public List<Map<String, Object>> getPendingApprovals() throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT proposal_id, approval_id, workflow_id, base_version, status, created_ts
                FROM approvals WHERE status IN ('pending','approved')
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(Map.of(
                        "proposalId",  rs.getString("proposal_id"),
                        "approvalId",  rs.getString("approval_id") != null ? rs.getString("approval_id") : "",
                        "workflowId",  rs.getString("workflow_id"),
                        "baseVersion", rs.getInt("base_version"),
                        "status",      rs.getString("status"),
                        "createdTs",   rs.getString("created_ts")
                ));
            }
        }
        return rows;
    }

    /** Get single approval row by proposalId. */
    public Map<String, Object> getApproval(String proposalId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT proposal_id, approval_id, workflow_id, base_version, status, summary, created_ts FROM approvals WHERE proposal_id=?")) {
            ps.setString(1, proposalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Map<String, Object> row = new HashMap<>();
                row.put("proposalId",  rs.getString("proposal_id"));
                row.put("approvalId",  rs.getString("approval_id"));
                row.put("workflowId",  rs.getString("workflow_id"));
                row.put("baseVersion", rs.getInt("base_version"));
                row.put("status",      rs.getString("status"));
                row.put("summary",     rs.getString("summary"));
                row.put("createdTs",   rs.getString("created_ts"));
                return row;
            }
        }
    }

    /** Count how many proposals have been created for this exact config and base_version. */
    public int countProposalsForConfig(String workflowId, int baseVersion, String configJson) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM approvals WHERE workflow_id=? AND base_version=? AND proposed_config_json=?")) {
            ps.setString(1, workflowId);
            ps.setInt(2, baseVersion);
            ps.setString(3, configJson);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count proposals: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Count proposals submitted for a workflow within the last windowHours of
     * simulated time (measured from currentSimTs). Used to enforce max_requests_per_24h.
     */
    public int countRecentProposals(String workflowId, String currentSimTs, int windowHours)
            throws SQLException {
        // Subtract windowHours from currentSimTs using SQLite datetime arithmetic
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) FROM approvals
                WHERE workflow_id = ?
                  AND status NOT IN ('rejected','expired')
                  AND created_ts >= datetime(?, '-' || ? || ' hours')
                """)) {
            ps.setString(1, workflowId);
            ps.setString(2, currentSimTs);
            ps.setInt(3, windowHours);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Audit log — append-only
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Append one audit record.
     *
     * @param tsSim       simulated timestamp (may be null for startup events)
     * @param workflowId  may be null for scenario-level events
     * @param kind        e.g. "supervisor_edit", "tick", "proposal_submitted"
     * @param payload     free-form map, serialised to JSON
     */
    public void audit(
            String tsSim, String workflowId, String kind, Map<String, Object> payload
    ) throws SQLException {
        String tsWall = Instant.now().toString();
        String payloadJson;
        try {
            payloadJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            payloadJson = "{\"error\":\"serialisation failed\"}";
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO audit_log(ts_sim, ts_wall, workflow_id, kind, payload_json)
                VALUES (?,?,?,?,?)
                """)) {
            ps.setString(1, tsSim);
            ps.setString(2, tsWall);
            ps.setString(3, workflowId);
            ps.setString(4, kind);
            ps.setString(5, payloadJson);
            ps.executeUpdate();
        }
    }

    /** Query recent audit rows for a workflow (most recent first). */
    public List<Map<String, Object>> queryAudit(String workflowId, int limit) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        String sql = workflowId != null
                ? "SELECT id,ts_sim,ts_wall,workflow_id,kind,payload_json FROM audit_log WHERE workflow_id=? ORDER BY id DESC LIMIT ?"
                : "SELECT id,ts_sim,ts_wall,workflow_id,kind,payload_json FROM audit_log ORDER BY id DESC LIMIT ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (workflowId != null) {
                ps.setString(1, workflowId);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(Map.of(
                            "id",         rs.getLong("id"),
                            "ts_sim",     rs.getString("ts_sim") != null ? rs.getString("ts_sim") : "",
                            "ts_wall",    rs.getString("ts_wall"),
                            "workflowId", rs.getString("workflow_id") != null ? rs.getString("workflow_id") : "",
                            "kind",       rs.getString("kind"),
                            "payload",    rs.getString("payload_json")
                    ));
                }
            }
        }
        return rows;
    }

    /** Total number of call events ingested — used by /health. */
    public long countCalls() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM calls");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Number of approvals in non-terminal state — used by /health. */
    public int countPendingApprovals() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM approvals WHERE status IN ('pending','approved')");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ── JDBC null helpers (SQLite JDBC requires explicit setNull) ─────────────

    private void setNullableInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.INTEGER);
        else           ps.setInt(i, v);
    }

    private void setNullableBool(PreparedStatement ps, int i, Boolean v) throws SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.INTEGER);
        else           ps.setInt(i, v ? 1 : 0);
    }

    private void setNullableDouble(PreparedStatement ps, int i, Double v) throws SQLException {
        if (v == null) ps.setNull(i, java.sql.Types.REAL);
        else           ps.setDouble(i, v);
    }
}
