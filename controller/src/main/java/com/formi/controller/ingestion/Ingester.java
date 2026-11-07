package com.formi.controller.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formi.controller.client.ApiException;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.AdvanceResult;
import com.formi.controller.client.model.ChunkMeta;
import com.formi.controller.db.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Idempotent, restartable event ingestion from the simulator.
 *
 * Key design decisions:
 *
 *  1. ONE database transaction per chunk — the cursor update and all event
 *     inserts happen atomically. If we crash mid-chunk the cursor stays at the
 *     previous value and we re-process the whole chunk on restart. INSERT OR
 *     IGNORE on event_id makes re-processing harmless (duplicates silently dropped).
 *
 *  2. Stream NDJSON line by line — SimClient.streamChunk() reads one line at a
 *     time so we never load a full chunk (potentially hundreds of MB at load scale)
 *     into memory.
 *
 *  3. Same code path for first start, restart, and history bootstrap — the
 *     processChunk() method is agnostic to whether a chunk is history or live.
 *     The cursor tells us where we are; everything else is just "process this chunk".
 *
 *  4. No fanout / handler list — we process events directly here. This is simpler
 *     and easier to audit than a pub/sub bus for a single-consumer loop.
 */
public class Ingester {

    private static final Logger log = LoggerFactory.getLogger(Ingester.class);

    private final SimClient  client;
    private final Store      store;
    private final Connection conn;   // needed for transaction control
    private final ObjectMapper mapper = new ObjectMapper();

    // Running counter for the /health endpoint (updated after each chunk)
    private long totalEventsIngested = 0L;

    public Ingester(SimClient client, Store store, Connection conn) {
        this.client = client;
        this.store  = store;
        this.conn   = conn;
    }

    public long getTotalEventsIngested() { return totalEventsIngested; }

    // ══════════════════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * On first start: fetch and process all history (kind=history) chunks exactly once.
     * Guarded by the "history_bootstrapped" flag in kv_state — idempotent.
     */
    public void bootstrapHistory() throws Exception {
        if (store.isHistoryBootstrapped()) {
            log.info("History already bootstrapped — skipping.");
            return;
        }
        log.info("Bootstrapping from history chunks…");
        List<ChunkMeta> chunks = client.listChunks("history", -1);
        // Sort ascending by chunk number so we process chronologically
        chunks.sort((a, b) -> Integer.compare(a.chunk, b.chunk));
        for (ChunkMeta ch : chunks) {
            processChunk(ch.chunk);
        }
        store.markHistoryBootstrapped();
        log.info("History bootstrap complete: {} chunks processed.", chunks.size());
    }

    /**
     * On startup (after history): ingest any event chunks the simulator produced
     * that we haven't processed yet (e.g. produced before a crash).
     * Uses the same cursor as the live loop — idempotent.
     */
    public void catchUpMissedChunks() throws Exception {
        int cursor = store.getEventCursor();
        List<ChunkMeta> missed = client.listChunks("events", cursor);
        if (missed.isEmpty()) {
            log.info("No missed event chunks after cursor={}.", cursor);
            return;
        }
        missed.sort((a, b) -> Integer.compare(a.chunk, b.chunk));
        log.info("Catching up {} missed event chunk(s) after cursor={}.", missed.size(), cursor);
        for (ChunkMeta ch : missed) {
            processChunk(ch.chunk);
        }
    }

    /**
     * Process the new_chunks list returned by POST /sim/advance.
     * Returns the number of new events ingested in this tick.
     */
    public int ingestNewChunks(List<AdvanceResult.ChunkMeta> newChunks) throws Exception {
        if (newChunks == null || newChunks.isEmpty()) return 0;

        // Sort ascending to stay chronological
        List<AdvanceResult.ChunkMeta> sorted = new ArrayList<>(newChunks);
        sorted.sort((a, b) -> Integer.compare(a.chunk, b.chunk));

        int cursor = store.getEventCursor();
        int ingested = 0;
        for (AdvanceResult.ChunkMeta ch : sorted) {
            if (ch.chunk <= cursor) {
                // We've already processed this chunk (can happen if advance response is replayed)
                log.debug("Chunk {} already processed (cursor={}), skipping.", ch.chunk, cursor);
                continue;
            }
            ingested += processChunk(ch.chunk);
        }
        return ingested;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Core: process one chunk atomically
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fetch chunk {@code chunkNo} and store all its events in one DB transaction.
     * The cursor is updated inside the same transaction.
     *
     * Crash safety: if we die anywhere inside this method, conn.rollback() fires
     * (either explicitly in the catch, or on JVM exit before commit). The cursor
     * stays at the previous value. On restart we re-fetch and re-process the same
     * chunk — INSERT OR IGNORE ensures duplicates are harmless.
     *
     * @return number of events parsed from the chunk (some may be duplicate-ignored)
     */
    int processChunk(int chunkNo) throws Exception {
        log.debug("Processing chunk {}…", chunkNo);

        // Count events processed in this chunk (for logging)
        int[] count = {0};
        List<Exception> errors = new ArrayList<>();

        boolean wasAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);  // begin transaction
        try {
            client.streamChunk(chunkNo, eventNode -> {
                try {
                    dispatchEvent(eventNode);
                    count[0]++;
                } catch (Exception e) {
                    // Collect but don't rethrow yet — we process remaining lines first
                    errors.add(e);
                    log.error("Error processing event in chunk {}: {}", chunkNo, e.getMessage(), e);
                }
            });

            if (!errors.isEmpty()) {
                // Any error in a chunk fails the whole chunk — we rollback and retry next run
                throw new RuntimeException(
                        "Chunk " + chunkNo + " had " + errors.size() + " processing error(s); rolling back.",
                        errors.get(0));
            }

            // Update cursor INSIDE the same transaction — this is the atomicity guarantee
            store.setEventCursor(chunkNo);
            conn.commit();

        } catch (Exception e) {
            conn.rollback();
            log.error("Rolled back chunk {} after error: {}", chunkNo, e.getMessage());
            throw e;
        } finally {
            conn.setAutoCommit(wasAutoCommit);  // restore whatever state we found
        }

        totalEventsIngested += count[0];
        log.debug("Chunk {} done: {} events.", chunkNo, count[0]);
        return count[0];
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Event dispatch — one method per event type
    // ══════════════════════════════════════════════════════════════════════════

    private void dispatchEvent(JsonNode ev) throws Exception {
        String type = text(ev, "type");
        switch (type) {
            case "lead"             -> handleLead(ev);
            case "call"             -> handleCall(ev);
            case "config_changed"   -> handleConfigChanged(ev);
            case "approval_decided" -> handleApprovalDecided(ev);
            case "campaign_created" -> handleCampaignCreated(ev);
            default                 -> log.trace("Ignoring event type: {}", type);
        }
    }

    // ── lead ─────────────────────────────────────────────────────────────────

    private void handleLead(JsonNode ev) throws SQLException {
        JsonNode feat = ev.path("features");
        store.insertLead(
                text(ev, "lead_id"),
                text(ev, "person_id"),
                text(ev, "workflow_id"),
                text(ev, "campaign_id"),
                text(feat, "lang"),
                text(feat, "city_tier"),
                text(feat, "age_band"),
                text(feat, "value_band")
        );
    }

    // ── call ─────────────────────────────────────────────────────────────────

    private void handleCall(JsonNode ev) throws SQLException {
        JsonNode variant = ev.path("variant");
        JsonNode qa      = ev.path("qa");   // only present if connected

        boolean connected = ev.path("connected").asBoolean(false);
        boolean engaged   = ev.path("engaged").asBoolean(false);

        Boolean hallucinationFlag = connected && qa.has("hallucination_flag")
                ? qa.get("hallucination_flag").asBoolean()
                : null;
        Double flowAdherence = connected && qa.has("flow_adherence")
                ? qa.get("flow_adherence").asDouble()
                : null;

        store.insertCall(
                ev.path("event_id").asLong(),
                text(ev, "ts"),
                text(ev, "workflow_id"),
                text(ev, "campaign_id"),
                text(ev, "lead_id"),
                text(ev, "person_id"),
                intOrNull(ev, "attempt_no"),
                intOrNull(ev, "config_version"),
                text(ev, "cohort_id"),
                text(variant, "welcome_message"),
                text(variant, "voice"),
                text(variant, "caller_id"),
                text(variant, "prompt_version"),
                connected,
                engaged,
                text(ev, "disposition"),
                hallucinationFlag,
                flowAdherence
        );
    }

    // ── config_changed ───────────────────────────────────────────────────────

    private void handleConfigChanged(JsonNode ev) throws Exception {
        String workflowId = text(ev, "workflow_id");
        String actor      = text(ev, "actor");
        int    version    = ev.path("version").asInt();
        String ts         = text(ev, "ts");
        String dimsJson   = mapper.writeValueAsString(ev.path("changed_dims"));

        // The event doesn't contain the full config — we store what we have.
        // The full config snapshot is fetched separately by the main loop.
        // For the config_versions table we store a placeholder; the loop updates it.
        String configJson = ev.toString();  // whole event as fallback

        store.insertConfigVersion(workflowId, version, configJson, actor, ts, dimsJson);

        // Supervisor edits always go into the audit log immediately so they're visible
        // even before the loop fetches the full config.
        if ("supervisor".equals(actor)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("actor",       actor);
            payload.put("version",     version);
            payload.put("changedDims", ev.path("changed_dims").toString());
            payload.put("event",       ev.toString());
            store.audit(ts, workflowId, "supervisor_edit", payload);
            log.info("Supervisor edit on {} → version {} dims={}", workflowId, version, dimsJson);
        }
    }

    // ── approval_decided ─────────────────────────────────────────────────────

    private void handleApprovalDecided(JsonNode ev) throws Exception {
        String approvalId = text(ev, "approval_id");
        String status     = text(ev, "status");
        String workflowId = text(ev, "workflow_id");
        String ts         = text(ev, "ts");
        String reasonsJson = mapper.writeValueAsString(ev.path("reasons"));

        // Update the approvals table using proposal_id if we can find the matching row,
        // otherwise fall back to approval_id.
        // The GovernanceService reconcile() will do a full sync; this is early notification.
        store.updateApprovalStatus(
                // We key the approvals table by proposal_id; look it up by approval_id
                findProposalIdByApprovalId(approvalId),
                status, ts, reasonsJson
        );

        Map<String, Object> payload = new HashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("status",     status);
        payload.put("reasons",    ev.path("reasons").toString());
        store.audit(ts, workflowId, "proposal_decided", payload);

        log.info("Approval {} for {} → {}", approvalId, workflowId, status);
    }

    // ── campaign_created ─────────────────────────────────────────────────────

    private void handleCampaignCreated(JsonNode ev) throws Exception {
        // Logged to audit so we know when batches arrive; no separate table in Day 1
        Map<String, Object> payload = new HashMap<>();
        payload.put("campaignId", text(ev, "campaign_id"));
        payload.put("leads",      ev.path("leads").asInt());
        payload.put("configVersionAtCreation", ev.path("config_version_at_creation").asInt());
        store.audit(text(ev, "ts"), text(ev, "workflow_id"), "campaign_created", payload);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Return node's text field or null if missing/null-JSON. */
    private String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isNull() || n.isMissingNode() ? null : n.asInt();
    }

    /**
     * Look up the proposal_id from our approvals table using the approval_id.
     * Returns the approval_id itself as fallback if not found (the update will be a no-op).
     */
    private String findProposalIdByApprovalId(String approvalId) {
        try {
            var pending = store.getPendingApprovals();
            for (var row : pending) {
                if (approvalId.equals(row.get("approvalId"))) {
                    return (String) row.get("proposalId");
                }
            }
        } catch (SQLException e) {
            log.warn("Could not look up proposal_id for approval {}: {}", approvalId, e.getMessage());
        }
        return approvalId;  // fallback: update will likely be a no-op
    }
}
