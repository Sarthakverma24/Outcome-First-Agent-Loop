package com.formi.controller.health;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared health snapshot — written by the main loop, read by the health server thread.
 *
 * We use an AtomicReference<Snapshot> so the health server always reads a consistent,
 * fully-written object without needing a lock. This is safe because Snapshot is immutable
 * (all fields final) and the reference itself is atomically swapped on each update.
 */
public class HealthState {

    public record Snapshot(
            String  lastTickTs,          // simulated ts of the last advance
            int     eventCursor,         // last fully-ingested chunk number
            long    totalEventsIngested, // cumulative events processed
            int     pendingApprovals,    // non-terminal proposals in DB
            long    ingestLagMs,         // wall-clock ms between last advance call and now
            int     creditsRemaining     // from GET /sim/state
    ) {}

    private final AtomicReference<Snapshot> ref =
            new AtomicReference<>(new Snapshot("none", -1, 0L, 0, 0L, 0));

    public void update(Snapshot s) { ref.set(s); }

    public Snapshot get() { return ref.get(); }
}
