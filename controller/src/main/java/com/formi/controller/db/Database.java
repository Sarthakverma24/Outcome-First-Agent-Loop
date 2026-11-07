package com.formi.controller.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite connection factory and schema bootstrapper.
 *
 * Design decisions:
 *
 *  1. WAL mode: Write-Ahead Logging lets the health-server thread read while the
 *     main loop writes, without blocking either side. Essential for /health to
 *     remain responsive during large chunk ingestion.
 *
 *  2. PRAGMA synchronous=NORMAL: Safe with WAL — we may lose the last committed
 *     transaction on an OS crash (not a process crash), which is acceptable because
 *     the cursor ensures we re-process any lost chunk on restart.
 *
 *  3. Single connection per thread: the main loop owns one connection; the health
 *     server uses an in-memory snapshot so it needs no DB connection at all.
 *
 *  4. Schema versioning: a simple integer in kv_state("schema_version").
 *     Each migration block is guarded by the version number, so the method is
 *     safe to call on every startup (idempotent).
 */
public class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** Current schema version. Increment when adding migrations below. */
    private static final int SCHEMA_VERSION = 2;

    private final String path;
    private Connection connection;

    public Database(String path) {
        this.path = path;
    }

    /**
     * Open (or create) the SQLite file, configure pragmas, and run schema migrations.
     * Safe to call on every startup.
     */
    public void init() throws SQLException {
        // sqlite-jdbc bundles the native library — no JDBC Class.forName() needed in Java 9+
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);

        try (Statement st = connection.createStatement()) {
            // WAL + NORMAL synchronous: safe crash recovery, low write latency
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
            // Increase page cache to ~16 MB to speed up large bulk ingests
            st.execute("PRAGMA cache_size=-16000");
        }

        applyMigrations();
        log.info("Database open: {} (schema v{})", path, SCHEMA_VERSION);
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // ── Schema migrations ─────────────────────────────────────────────────────

    private void applyMigrations() throws SQLException {
        // Ensure the kv table exists first so we can read the version
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS kv_state (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);
        }

        int current = getCurrentSchemaVersion();
        log.debug("Schema: current={} target={}", current, SCHEMA_VERSION);

        if (current < 1) {
            applyV1();
            setSchemaVersion(1);
        }
        if (current < 2) {
            applyV2();
            setSchemaVersion(2);
        }
        // Add `if (current < 3) { applyV3(); setSchemaVersion(3); }` here for future migrations
    }

    /**
     * V1: all tables for Day 1 foundation.
     * Uses CREATE TABLE IF NOT EXISTS throughout — idempotent if somehow called twice.
     */
    private void applyV1() throws SQLException {
        log.info("Applying schema migration v1…");
        try (Statement st = connection.createStatement()) {

            // ── leads ─────────────────────────────────────────────────────────
            // One row per (lead_id). person_id is stable across campaigns so joins
            // on person_id work for per-person learning in later days.
            st.execute("""
                CREATE TABLE IF NOT EXISTS leads (
                    lead_id    TEXT PRIMARY KEY,
                    person_id  TEXT NOT NULL,
                    workflow_id TEXT NOT NULL,
                    campaign_id TEXT NOT NULL,
                    lang        TEXT,
                    city_tier   TEXT,
                    age_band    TEXT,
                    value_band  TEXT
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_leads_wf ON leads(workflow_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_leads_person ON leads(person_id)");

            // ── calls ─────────────────────────────────────────────────────────
            // event_id is the primary key — INSERT OR IGNORE makes ingestion
            // idempotent. All variant dimensions are stored flat so SQL aggregations
            // (COUNT, AVG grouped by voice, cohort_id, etc.) are straightforward.
            st.execute("""
                CREATE TABLE IF NOT EXISTS calls (
                    event_id          INTEGER PRIMARY KEY,
                    ts                TEXT    NOT NULL,
                    workflow_id       TEXT    NOT NULL,
                    campaign_id       TEXT    NOT NULL,
                    lead_id           TEXT,
                    person_id         TEXT,
                    attempt_no        INTEGER,
                    config_version    INTEGER,
                    cohort_id         TEXT,
                    welcome_message   TEXT,
                    voice             TEXT,
                    caller_id         TEXT,
                    prompt_version    TEXT,
                    connected         INTEGER NOT NULL,  -- 0/1 (SQLite has no BOOLEAN)
                    engaged           INTEGER NOT NULL,  -- 0/1
                    disposition       TEXT,
                    hallucination_flag INTEGER,          -- NULL if not connected
                    flow_adherence    REAL                -- NULL if not connected
                )
            """);
            // Index by (workflow_id, ts) for time-window aggregations
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_calls_wf_ts
                    ON calls(workflow_id, ts)
            """);
            // Index by variant dimensions for A/B comparisons
            st.execute("""
                CREATE INDEX IF NOT EXISTS idx_calls_variant
                    ON calls(workflow_id, cohort_id, voice, welcome_message, caller_id)
            """);

            // ── config_versions ───────────────────────────────────────────────
            // Every config change event is recorded verbatim.
            // actor: "supervisor" | "controller_approved" | "controller_auto"
            st.execute("""
                CREATE TABLE IF NOT EXISTS config_versions (
                    workflow_id   TEXT NOT NULL,
                    version       INTEGER NOT NULL,
                    config_json   TEXT NOT NULL,
                    actor         TEXT NOT NULL,
                    ts            TEXT NOT NULL,
                    changed_dims  TEXT,           -- JSON array of dimension names
                    PRIMARY KEY (workflow_id, version)
                )
            """);

            // ── approvals ─────────────────────────────────────────────────────
            // Mirrors the simulator's approval lifecycle. proposal_id is our
            // deterministic key; approval_id is assigned by the simulator.
            st.execute("""
                CREATE TABLE IF NOT EXISTS approvals (
                    proposal_id        TEXT PRIMARY KEY,
                    approval_id        TEXT,
                    workflow_id        TEXT NOT NULL,
                    base_version       INTEGER NOT NULL,
                    status             TEXT NOT NULL DEFAULT 'pending',
                    proposed_config_json TEXT NOT NULL,
                    summary            TEXT NOT NULL,
                    rationale          TEXT,
                    created_ts         TEXT NOT NULL,  -- simulated ts when submitted
                    decided_ts         TEXT,
                    reasons_json       TEXT,           -- JSON array of rejection reasons
                    applied_ts         TEXT            -- wall-clock ts of successful apply
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_approvals_wf ON approvals(workflow_id, status)");

            // ── audit_log ─────────────────────────────────────────────────────
            // Append-only. Every meaningful event the controller observes or acts on.
            // "Why did workflow X change at 14:00 on D3?" starts here.
            // kind examples: "tick", "config_changed", "supervisor_edit",
            //                "proposal_submitted", "proposal_decided", "applied",
            //                "intent_to_apply", "apply_failed"
            st.execute("""
                CREATE TABLE IF NOT EXISTS audit_log (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts_sim       TEXT,           -- simulated timestamp
                    ts_wall      TEXT NOT NULL,  -- wall-clock timestamp (UTC ISO-8601)
                    workflow_id  TEXT,
                    kind         TEXT NOT NULL,
                    payload_json TEXT            -- free-form JSON with all relevant fields
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_wf ON audit_log(workflow_id, ts_sim)");
        }
        log.info("Schema migration v1 complete.");
    }

    /**
     * V2: Day 2 brain tables.
     * Uses CREATE TABLE IF NOT EXISTS — idempotent if called on an existing DB.
     */
    private void applyV2() throws SQLException {
        log.info("Applying schema migration v2 (Day 2 brain tables)…");
        try (Statement st = connection.createStatement()) {

            // ── experiments ───────────────────────────────────────────────
            // One row per A/B experiment. experiment_id is deterministic (SHA-256)
            // so a crash-and-restart cannot create a duplicate experiment for the
            // same (workflow, cohort, dimension, start_day) combination.
            st.execute("""
                CREATE TABLE IF NOT EXISTS experiments (
                    experiment_id       TEXT PRIMARY KEY,
                    workflow_id         TEXT NOT NULL,
                    cohort_id           TEXT NOT NULL,
                    dimension           TEXT NOT NULL,  -- "voice" | "welcome_message"
                    control_value       TEXT NOT NULL,  -- value of the control arm
                    challenger_values   TEXT NOT NULL,  -- JSON array of challenger values
                    status              TEXT NOT NULL DEFAULT 'running',
                        -- running | winner_proposed | winner_pending | winner_applied
                        -- | rolled_back | contaminated | expired
                    start_sim_ts        TEXT NOT NULL,
                    end_sim_ts          TEXT,           -- set when status becomes terminal
                    last_look_day       TEXT,           -- "YYYY-MM-DD" of last evaluation look
                    proposal_id         TEXT,           -- FK to approvals (our proposal_id)
                    winner_value        TEXT,           -- set when status = winner_*
                    apply_sim_ts        TEXT,           -- simTs when approval was applied
                    contaminated_reason TEXT            -- supervisor edit ref or similar
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_exp_wf_status ON experiments(workflow_id, status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_exp_wf_cohort_dim ON experiments(workflow_id, cohort_id, dimension)");
        }
        log.info("Schema migration v2 complete.");
    }

    // ── Versioning helpers ────────────────────────────────────────────────────

    private int getCurrentSchemaVersion() throws SQLException {
        try (var ps = connection.prepareStatement(
                "SELECT value FROM kv_state WHERE key='schema_version'");
             var rs = ps.executeQuery()) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    private void setSchemaVersion(int v) throws SQLException {
        try (var ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO kv_state(key,value) VALUES('schema_version',?)")) {
            ps.setString(1, String.valueOf(v));
            ps.executeUpdate();
        }
    }
}
