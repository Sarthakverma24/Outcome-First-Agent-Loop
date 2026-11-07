package com.formi.controller.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One entry from GET /events/chunks?kind=events|history&after=n.
 * We only need the chunk number and kind to know what to fetch next.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChunkMeta {
    public int chunk;
    public String kind;   // "events" or "history"
}
