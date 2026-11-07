package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response from POST /sim/advance.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdvanceResult {
    /** New simulated timestamp after the advance. */
    public String ts;
    /** True if this advance reached the scenario end. */
    public boolean finished;
    /** Chunks produced during this advance step. May be empty. */
    public List<ChunkMeta> newChunks;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChunkMeta {
        /** Global, monotonically increasing chunk number. */
        public int chunk;
        /** "events" for live events; "history" for pre-D0 history. */
        public String kind;
    }
}
