package com.formi.controller.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formi.controller.client.ApiException;
import com.formi.controller.client.SimClient;
import com.formi.controller.client.model.AdvanceResult;
import com.formi.controller.db.Database;
import com.formi.controller.db.Store;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Ingester idempotency and crash-rollback behaviour.
 *
 * We use an in-memory SQLite database (:memory:) so tests are isolated and fast.
 * SimClient is replaced by a fake that serves pre-canned NDJSON lines.
 */
class IngesterIdempotencyTest {

    private Database db;
    private Store    store;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        db   = new Database(":memory:");
        db.init();
        conn  = db.getConnection();
        store = new Store(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    // ── Test 1: processing the same chunk twice leaves row counts unchanged ───

    @Test
    void processingSameChunkTwiceIsIdempotent() throws Exception {
        // Arrange: two call events and one lead event in the fake chunk
        List<JsonNode> fakeEvents = makeFakeChunk(
                makeLeadEvent(1, "lead-001", "person-001", "wf-a", "camp-01"),
                makeCallEvent(2, "wf-a", "camp-01", "lead-001", true, true),
                makeCallEvent(3, "wf-a", "camp-01", "lead-001", false, false)
        );

        FakeSimClient fakeClient = new FakeSimClient(fakeEvents);
        Ingester ingester = new Ingester(fakeClient, store, conn);

        // Act: process chunk 1 twice
        ingester.processChunk(1);
        ingester.processChunk(1);  // second call — all inserts are OR IGNORE

        // Assert: exactly 1 lead and 2 calls regardless of how many times we processed
        assertEquals(1, countRows("leads"), "Expected exactly 1 lead row");
        assertEquals(2, countRows("calls"), "Expected exactly 2 call rows");

        // Cursor should be 1 (the last chunk we processed)
        assertEquals(1, store.getEventCursor(), "Cursor should be at chunk 1");
    }

    // ── Test 2: cursor advances correctly across multiple distinct chunks ─────

    @Test
    void cursorAdvancesPerChunk() throws Exception {
        List<JsonNode> chunk1Events = makeFakeChunk(makeCallEvent(10, "wf-b", "c", "l", true, false));
        List<JsonNode> chunk2Events = makeFakeChunk(makeCallEvent(11, "wf-b", "c", "l", false, false));

        // Chunk 1
        new Ingester(new FakeSimClient(chunk1Events), store, conn).processChunk(1);
        assertEquals(1, store.getEventCursor());

        // Chunk 2
        new Ingester(new FakeSimClient(chunk2Events), store, conn).processChunk(2);
        assertEquals(2, store.getEventCursor());
    }

    // ── Test 3: crash mid-chunk rolls back — cursor and rows stay unchanged ───

    /**
     * Simulates a crash by having the fake client throw an exception after emitting
     * the first event line. Verifies that:
     *   - No rows are inserted (transaction rolled back)
     *   - Cursor stays at its pre-crash value
     */
    @Test
    void crashMidChunkRollsBackEverything() throws Exception {
        // Pre-condition: cursor is at 0 (a previous chunk was done)
        store.setEventCursor(0);

        // Fake client that emits one event then throws
        AtomicInteger callCount = new AtomicInteger();
        FakeSimClient crashingClient = new FakeSimClient(List.of()) {
            @Override
            public void streamChunk(int chunkNo, Consumer<JsonNode> consumer)
                    throws ApiException, java.io.IOException, InterruptedException {
                // Emit one event successfully
                try { consumer.accept(makeCallEvent(99, "wf-crash", "c", "l", true, true)); }
                catch (Exception e) { throw new java.io.IOException("wrap", e); }
                callCount.incrementAndGet();
                // Then simulate a network or parse failure mid-chunk
                throw new java.io.IOException("simulated crash mid-chunk");
            }
        };

        Ingester ingester = new Ingester(crashingClient, store, conn);

        // Act: processing should throw
        assertThrows(Exception.class, () -> ingester.processChunk(1));

        // Assert: nothing was written — the transaction was rolled back
        assertEquals(0, countRows("calls"),  "No call rows should exist after rollback");
        assertEquals(0, store.getEventCursor(),  "Cursor must stay at pre-crash value (0)");
    }

    // ── Test 4: ingestNewChunks skips already-seen chunks ────────────────────

    @Test
    void ingestNewChunksSkipsProcessedChunks() throws Exception {
        store.setEventCursor(5);  // simulate: chunks 0-5 already done

        // Use a chunk-aware fake that returns a different event_id per chunk
        // so INSERT OR IGNORE doesn't de-dup across chunks.
        FakeSimClient fakeClient = new FakeSimClient(List.of()) {
            @Override
            public void streamChunk(int chunkNo, Consumer<JsonNode> consumer)
                    throws ApiException, java.io.IOException, InterruptedException {
                try {
                    // event_id = chunkNo * 100 — unique per chunk
                    consumer.accept(makeCallEvent(chunkNo * 100L, "wf-x", "c", "l", true, false));
                } catch (Exception e) { throw new java.io.IOException("wrap", e); }
            }
        };
        Ingester ingester = new Ingester(fakeClient, store, conn);

        // Build an AdvanceResult.ChunkMeta list that includes already-processed chunks
        List<AdvanceResult.ChunkMeta> newChunks = new ArrayList<>();
        for (int i = 3; i <= 7; i++) {   // chunks 3,4,5 already done; 6,7 are new
            var cm = new AdvanceResult.ChunkMeta();
            cm.chunk = i;
            cm.kind  = "events";
            newChunks.add(cm);
        }

        ingester.ingestNewChunks(newChunks);

        // Only chunks 6 and 7 should have been processed → 2 distinct rows
        assertEquals(2, countRows("calls"), "Only 2 new chunks should have been processed");
        assertEquals(7, store.getEventCursor(), "Cursor should advance to 7");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    private int countRows(String table) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SafeVarargs
    private List<JsonNode> makeFakeChunk(JsonNode... events) {
        return List.of(events);
    }

    private static JsonNode makeLeadEvent(long eventId, String leadId, String personId,
                                          String workflowId, String campaignId) throws Exception {
        return MAPPER.readTree(String.format("""
            {"type":"lead","event_id":%d,"ts":"2027-01-05T09:00:00+05:30",
             "workflow_id":"%s","campaign_id":"%s",
             "lead_id":"%s","person_id":"%s",
             "features":{"lang":"hi","city_tier":"1","age_band":"31-45","value_band":"mid"}}
            """, eventId, workflowId, campaignId, leadId, personId));
    }

    private static JsonNode makeCallEvent(long eventId, String workflowId, String campaignId,
                                          String leadId, boolean connected, boolean engaged) throws Exception {
        return MAPPER.readTree(String.format("""
            {"type":"call","event_id":%d,"ts":"2027-01-05T10:00:00+05:30",
             "workflow_id":"%s","campaign_id":"%s","lead_id":"%s","person_id":"p-001",
             "attempt_no":1,"config_version":1,"cohort_id":"default",
             "variant":{"welcome_message":"W1","voice":"V1","caller_id":"D1","prompt_version":"P1"},
             "connected":%s,"engaged":%s,"disposition":"concluded_interested",
             "qa":{"hallucination_flag":false,"flow_adherence":0.95}}
            """, eventId, workflowId, campaignId, leadId, connected, engaged));
    }

    // ── Minimal fake SimClient ────────────────────────────────────────────────

    /**
     * Fake SimClient for tests. Only overrides streamChunk — all other methods
     * throw UnsupportedOperationException so a test that accidentally calls them fails loudly.
     */
    static class FakeSimClient extends SimClient {
        private final List<JsonNode> events;

        FakeSimClient(List<JsonNode> events) {
            super("http://fake-sim-not-used");
            this.events = events;
        }

        @Override
        public void streamChunk(int chunkNo, Consumer<JsonNode> consumer)
                throws ApiException, java.io.IOException, InterruptedException {
            for (JsonNode ev : events) consumer.accept(ev);
        }
    }
}
