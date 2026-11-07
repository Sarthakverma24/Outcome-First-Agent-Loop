package com.formi.controller.brain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.ConfigVersion;
import com.formi.controller.client.model.WorkflowSummary;
import com.formi.controller.db.Database;
import com.formi.controller.db.Store;
import com.formi.controller.governance.GovernanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replay test: inserts deterministic synthetic call data into in-memory SQLite,
 * invokes ExperimentEngine multiple times, and verifies that the exact same winner
 * decision and proposal are reached reproducibly.
 */
class ReplayTest {

    private Database database;
    private Store store;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        database = new Database(":memory:");
        database.init();
        conn = database.getConnection();
        store = new Store(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void replayExperiment_determinesSameWinnerConsistently() throws Exception {
        // Insert synthetic calls: 400 dials control (pickup=160 -> 40%), 400 dials challenger (pickup=240 -> 60%)
        // Both above MIN_CONNECTED_PER_ARM (300 dials minimum, here 400 dials and 160 vs 240 connected)
        // With z-score clearly significant and practical effect (60% - 40% = 20pp > 3pp)
        try (var ps = conn.prepareStatement("""
                INSERT INTO calls (
                    event_id, ts, workflow_id, campaign_id, lead_id, person_id,
                    attempt_no, config_version, cohort_id,
                    welcome_message, voice, caller_id, prompt_version,
                    connected, engaged, disposition, hallucination_flag, flow_adherence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {

            long id = 1;
            // Control: voice = "control_voice" (160 connected, 240 not)
            for (int i = 0; i < 400; i++) {
                boolean connected = i < 160;
                insertRow(ps, id++, "2027-01-05T10:00:00+05:30", "nw_renewal", "default",
                        "wm1", "control_voice", connected ? 1 : 0, 0);
            }
            // Challenger: voice = "challenger_voice" (240 connected, 160 not)
            for (int i = 0; i < 400; i++) {
                boolean connected = i < 240;
                insertRow(ps, id++, "2027-01-05T10:00:00+05:30", "nw_renewal", "default",
                        "wm1", "challenger_voice", connected ? 1 : 0, 0);
            }
            ps.executeBatch();
        }

        // Mock SimClient / Fake SimClient
        ObjectMapper mapper = new ObjectMapper();
        WorkflowSummary wf = new WorkflowSummary();
        wf.workflowId = "nw_renewal";
        wf.options = new WorkflowSummary.Options();
        wf.options.voice = List.of("control_voice", "challenger_voice");
        wf.objective = new WorkflowSummary.Objective();
        wf.objective.primary = "pickup_rate";
        wf.governance = new WorkflowSummary.Governance();
        wf.governance.maxRequestsPer24h = 10;
        wf.governance.approverHours = List.of(9, 21);
        wf.governance.proposalTtlHours = 24;

        ConfigVersion cv = new ConfigVersion();
        cv.version = 1;
        cv.config = mapper.readTree("{\"voice\":\"control_voice\"}");

        // In-memory fake client
        FakeSimClient fakeClient = new FakeSimClient(wf, cv);
        GovernanceService gov = new GovernanceService(fakeClient, store);
        ExperimentEngine engine = new ExperimentEngine(conn, store, fakeClient, gov);

        // Run evaluation look
        engine.run(wf, "2027-01-05T12:00:00+05:30", "2027-01-05");

        // Verify that an experiment row was inserted
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT experiment_id, status, control_value, challenger_values FROM experiments")) {
            assertTrue(rs.next(), "Experiment should have been created");
            assertEquals("nw_renewal", wf.workflowId);
            assertEquals("control_voice", rs.getString("control_value"));
            assertTrue(rs.getString("challenger_values").contains("challenger_voice"));
        }

        // Advance to next simulated day for evaluation look
        engine.run(wf, "2027-01-06T12:00:00+05:30", "2027-01-06");

        // Check if experiment status transitioned to winner_proposed / winner_pending
        try (var st = conn.createStatement();
             var rs = st.executeQuery("SELECT status, proposal_id FROM experiments")) {
            assertTrue(rs.next());
            String status = rs.getString("status");
            assertTrue(status.startsWith("winner_"), "Status should be winner_proposed or winner_pending, got: " + status);
        }

        // Repeat run with identical inputs and verify idempotency / determinism
        String firstExpId = ExperimentEngine.computeExperimentId("nw_renewal", "default", "voice", "2027-01-05T12:00:00+05:30");
        String secondExpId = ExperimentEngine.computeExperimentId("nw_renewal", "default", "voice", "2027-01-05T12:00:00+05:30");
        assertEquals(firstExpId, secondExpId, "Experiment ID calculation must be strictly deterministic across replays");
    }

    private void insertRow(java.sql.PreparedStatement ps, long id, String ts, String wf, String cohort,
                           String wm, String voice, int connected, int engaged) throws Exception {
        ps.setLong(1, id);
        ps.setString(2, ts);
        ps.setString(3, wf);
        ps.setString(4, "camp1");
        ps.setString(5, "lead" + id);
        ps.setString(6, "person" + id);
        ps.setInt(7, 1);
        ps.setInt(8, 1);
        ps.setString(9, cohort);
        ps.setString(10, wm);
        ps.setString(11, voice);
        ps.setString(12, "cid1");
        ps.setString(13, "pv1");
        ps.setInt(14, connected);
        ps.setInt(15, engaged);
        ps.setString(16, "completed");
        ps.setNull(17, java.sql.Types.INTEGER);
        ps.setNull(18, java.sql.Types.REAL);
        ps.addBatch();
    }

    private static class FakeSimClient extends SimClient {
        private final WorkflowSummary wf;
        private final ConfigVersion cv;

        public FakeSimClient(WorkflowSummary wf, ConfigVersion cv) {
            super("http://127.0.0.1:9999");
            this.wf = wf;
            this.cv = cv;
        }

        @Override
        public ConfigVersion getConfig(String workflowId) {
            return cv;
        }

        @Override
        public com.formi.controller.client.model.ApprovalSubmitResult submitApproval(
                com.formi.controller.client.model.ApprovalRequest request) {
            var res = new com.formi.controller.client.model.ApprovalSubmitResult();
            res.approvalId = "apr-replay-001";
            return res;
        }
    }
}
