package com.formi.controller.governance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formi.controller.client.*;
import com.formi.controller.client.model.*;
import com.formi.controller.db.Database;
import com.formi.controller.db.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GovernanceService state machine transitions.
 *
 * Uses in-memory SQLite and a configurable FakeSimClient so tests are
 * deterministic and require no running simulator.
 */
class GovernanceServiceTest {

    private Database db;
    private Store    store;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach void setUp() throws Exception {
        db    = new Database(":memory:");
        db.init();
        store = new Store(db.getConnection());
    }

    @AfterEach void tearDown() throws Exception { db.close(); }

    // ── Test 1: happy path — pending → approved → applied ────────────────────

    @Test
    void happyPath_pendingApprovedApplied() throws Exception {
        // Compute the deterministic proposal_id that GovernanceService will use
        JsonNode config = makeConfig();
        String proposalId = GovernanceService.computeProposalId("wf-a", 1, config);

        FakeSimClient fake = new FakeSimClient()
                .onSubmit(r -> successResult("apr-001"))
                .onGetApproval("apr-001", approvedStatus(proposalId, "apr-001", "wf-a"));

        GovernanceService gov = new GovernanceService(fake, store);
        WorkflowSummary wf = makeWorkflow("wf-a", List.of(9, 19), 3, 24, 2);

        // Submit
        String approvalId = gov.propose(wf, 1, config, "Test change",
                Map.of("key", "val"), 100, Map.of("note", "dry"), 0.5,
                "rationale", null, "2027-01-05T11:00:00+05:30");

        assertEquals("apr-001", approvalId);

        // Reconcile returns the approved row
        List<Map<String, Object>> ready = gov.reconcile("2027-01-05T12:00:00+05:30");
        assertEquals(1, ready.size());

        // Apply
        fake.onApply("wf-a", "apr-001", () -> {});  // apply succeeds
        String rowProposalId = (String) ready.get(0).get("proposalId");
        boolean applied = gov.apply(rowProposalId, "apr-001", "wf-a", "2027-01-05T12:00:00+05:30");
        assertTrue(applied);

        // DB should show no pending approvals
        var pending = store.getPendingApprovals();
        assertTrue(pending.isEmpty(), "No pending approvals after successful apply");
    }

    // ── Test 2: stale base version on apply ───────────────────────────────────

    @Test
    void applyFails_staleBaseVersion() throws Exception {
        FakeSimClient fake = new FakeSimClient()
                .onSubmit(r -> successResult("apr-002"))
                .onGetApproval("apr-002", approvedStatus("prop-stale", "apr-002", "wf-b"))
                .onApply("wf-b", "apr-002", () -> {
                    throw new StaleBaseVersionException("config changed");
                });

        GovernanceService gov = new GovernanceService(fake, store);
        WorkflowSummary wf = makeWorkflow("wf-b", List.of(9, 19), 3, 24, 2);

        gov.propose(wf, 1, makeConfig(), "s", Map.of("k","v"), 0, Map.of("n","r"), 0.1, "r", null,
                "2027-01-05T11:00:00+05:30");

        boolean applied = gov.apply("prop-stale", "apr-002", "wf-b", "2027-01-05T12:00:00+05:30");
        assertFalse(applied, "Apply should return false on stale base version");
    }

    // ── Test 3: lapsed approval ───────────────────────────────────────────────

    @Test
    void applyFails_lapsed() throws Exception {
        FakeSimClient fake = new FakeSimClient()
                .onSubmit(r -> successResult("apr-003"))
                .onApply("wf-c", "apr-003", () -> { throw new LapsedException("lapsed"); });

        GovernanceService gov = new GovernanceService(fake, store);
        WorkflowSummary wf = makeWorkflow("wf-c", List.of(9, 19), 3, 24, 2);

        gov.propose(wf, 1, makeConfig(), "s", Map.of("k","v"), 0, Map.of("n","r"), 0.1, "r", null,
                "2027-01-05T11:00:00+05:30");

        boolean applied = gov.apply("whatever", "apr-003", "wf-c", "2027-01-05T12:00:00+05:30");
        assertFalse(applied, "Apply should return false when approval lapsed");
    }

    // ── Test 4: duplicate proposal_id — adopt existing ────────────────────────

    @Test
    void duplicateProposalId_adoptsExisting() throws Exception {
        // The existing approval that the API has
        ApprovalStatus existing = new ApprovalStatus();
        existing.approvalId  = "apr-existing";
        existing.proposalId  = GovernanceService.computeProposalId("wf-d", 1, makeConfig());
        existing.workflowId  = "wf-d";
        existing.status      = "pending";
        existing.reasons     = List.of();

        FakeSimClient fake = new FakeSimClient()
                .onSubmit(r -> { throw new DuplicateProposalException("already exists"); })
                .onListApprovals("wf-d", null, List.of(existing));

        GovernanceService gov = new GovernanceService(fake, store);
        WorkflowSummary wf = makeWorkflow("wf-d", List.of(9, 19), 3, 24, 2);

        String approvalId = gov.propose(wf, 1, makeConfig(), "s", Map.of("k","v"), 0,
                Map.of("n","r"), 0.1, "r", null, "2027-01-05T11:00:00+05:30");

        assertEquals("apr-existing", approvalId, "Should adopt the existing approval_id");
    }

    // ── Test 5: proposal rejected — reconcile shows no ready-to-apply ─────────

    @Test
    void rejectedProposal_notOfferedForApply() throws Exception {
        ApprovalStatus rejected = new ApprovalStatus();
        rejected.approvalId = "apr-005";
        rejected.proposalId = "prop-005";
        rejected.workflowId = "wf-e";
        rejected.status     = "rejected";
        rejected.reasons    = List.of("poor rationale");

        FakeSimClient fake = new FakeSimClient()
                .onSubmit(r -> successResult("apr-005"))
                .onGetApproval("apr-005", rejected);

        GovernanceService gov = new GovernanceService(fake, store);
        WorkflowSummary wf = makeWorkflow("wf-e", List.of(9, 19), 3, 24, 2);

        gov.propose(wf, 1, makeConfig(), "s", Map.of("k","v"), 0, Map.of("n","r"), 0.1, "r", null,
                "2027-01-05T11:00:00+05:30");

        List<Map<String, Object>> ready = gov.reconcile("2027-01-05T12:00:00+05:30");
        assertTrue(ready.isEmpty(), "Rejected proposal should not be in ready-to-apply list");
    }

    // ── Test 6: proposal skipped outside approver hours ───────────────────────

    @Test
    void proposalSkipped_outsideApproverHours() throws Exception {
        FakeSimClient fake = new FakeSimClient();  // submit should not be called
        GovernanceService gov = new GovernanceService(fake, store);

        // Approver works 10–18; we submit at 09:00
        WorkflowSummary wf = makeWorkflow("wf-f", List.of(10, 18), 3, 24, 2);

        String result = gov.propose(wf, 1, makeConfig(), "s", Map.of("k","v"), 0,
                Map.of("n","r"), 0.1, "r", null, "2027-01-05T09:00:00+05:30");

        assertNull(result, "Proposal should be skipped outside approver hours");
        assertFalse(fake.submitCalled, "Submit should not have been called");
    }

    // ── Test 7: deterministic proposal ID ────────────────────────────────────

    @Test
    void proposalId_isDeterministic() throws Exception {
        JsonNode config = makeConfig();
        String id1 = GovernanceService.computeProposalId("wf-x", 3, config);
        String id2 = GovernanceService.computeProposalId("wf-x", 3, config);
        assertEquals(id1, id2, "Same inputs must produce same proposal_id");
        assertTrue(id1.startsWith("ctrl-"), "Proposal ID should have 'ctrl-' prefix");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Builders / helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static JsonNode makeConfig() throws Exception {
        return MAPPER.readTree("{\"allocations\":{\"default\":{\"voice\":{\"V1\":1}}},\"prompt_version\":\"P1\"}");
    }

    private static ApprovalSubmitResult successResult(String approvalId) {
        ApprovalSubmitResult r = new ApprovalSubmitResult();
        r.approvalId = approvalId;
        return r;
    }

    private static ApprovalStatus approvedStatus(String proposalId, String approvalId, String workflowId) {
        ApprovalStatus s = new ApprovalStatus();
        s.approvalId = approvalId;
        s.proposalId = proposalId;
        s.workflowId = workflowId;
        s.status     = "approved";
        s.reasons    = List.of();
        s.applyBy    = "2027-01-05T23:59:00+05:30";
        return s;
    }

    private static WorkflowSummary makeWorkflow(String id, List<Integer> approverHours,
                                                 int maxReqs, int ttlHours, int applyWindow) {
        WorkflowSummary w = new WorkflowSummary();
        w.workflowId = id;
        w.governance = new WorkflowSummary.Governance();
        w.governance.approverHours        = approverHours;
        w.governance.maxRequestsPer24h    = maxReqs;
        w.governance.proposalTtlHours     = ttlHours;
        w.governance.applyWindowHours     = applyWindow;
        w.governance.baseRejectRate       = 0.1;
        w.governance.autoApply            = new WorkflowSummary.AutoApply();
        w.governance.autoApply.enabled    = false;
        w.governance.autoApply.allowedDims = List.of();
        w.diallingWindow = List.of(9, 21);
        w.options = new WorkflowSummary.Options();
        w.options.voice = List.of("V1");
        return w;
    }

    // ── Fake SimClient ────────────────────────────────────────────────────────

    interface SubmitHandler { ApprovalSubmitResult handle(ApprovalRequest req) throws ApiException, IOException, InterruptedException; }
    interface ApplyHandler  { void handle() throws ApiException, IOException, InterruptedException; }

    static class FakeSimClient extends SimClient {
        boolean submitCalled = false;

        private SubmitHandler submitHandler;
        private final java.util.Map<String, ApprovalStatus> approvalMap = new java.util.HashMap<>();
        private final java.util.Map<String, ApplyHandler>   applyMap    = new java.util.HashMap<>();
        private List<ApprovalStatus> listAllApprovals = new ArrayList<>();

        FakeSimClient() { super("http://fake"); }

        FakeSimClient onSubmit(SubmitHandler h)                               { this.submitHandler = h; return this; }
        FakeSimClient onGetApproval(String id, ApprovalStatus s)              { approvalMap.put(id, s); return this; }
        FakeSimClient onApply(String wfId, String apId, ApplyHandler h)       { applyMap.put(apId, h); return this; }
        FakeSimClient onListApprovals(String wfId, String status, List<ApprovalStatus> list) { listAllApprovals = list; return this; }

        @Override public ApprovalSubmitResult submitApproval(ApprovalRequest req) throws ApiException, IOException, InterruptedException {
            submitCalled = true;
            if (submitHandler == null) throw new UnsupportedOperationException("No submit handler set");
            return submitHandler.handle(req);
        }

        @Override public ApprovalStatus getApproval(String id) throws ApiException, IOException, InterruptedException {
            var s = approvalMap.get(id);
            if (s == null) throw new ApiException(404, "not_found", "no such approval: " + id);
            return s;
        }

        @Override public void applyApproval(String workflowId, String approvalId) throws ApiException, IOException, InterruptedException {
            var h = applyMap.get(approvalId);
            if (h == null) return;  // default: success (no-op)
            h.handle();
        }

        @Override public List<ApprovalStatus> listApprovals(String wfId, String status) {
            return listAllApprovals;
        }

        @Override public void streamChunk(int chunkNo, Consumer<JsonNode> consumer) {}
    }
}
