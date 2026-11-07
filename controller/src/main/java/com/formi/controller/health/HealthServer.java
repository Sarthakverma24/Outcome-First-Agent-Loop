package com.formi.controller.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server exposing /health as JSON.
 *
 * Uses com.sun.net.httpserver.HttpServer — built into the JDK, zero extra
 * dependency. For a production service we'd use a real embedded server (Jetty,
 * Undertow), but the assignment says "no Spring, no ORM" and explicitly allows
 * the JDK HTTP server for the health endpoint.
 *
 * The handler reads from HealthState (AtomicReference) — no DB query, no lock,
 * always fast regardless of what the main loop is doing.
 */
public class HealthServer {

    private static final Logger log = LoggerFactory.getLogger(HealthServer.class);

    private final int         port;
    private final HealthState state;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    public HealthServer(int port, HealthState state) {
        this.port  = port;
        this.state = state;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), /*backlog*/ 10);
        server.createContext("/health", exchange -> {
            try {
                HealthState.Snapshot snap = state.get();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status",              "ok");
                body.put("lastTickTs",          snap.lastTickTs());
                body.put("eventCursor",         snap.eventCursor());
                body.put("totalEventsIngested", snap.totalEventsIngested());
                body.put("pendingApprovals",    snap.pendingApprovals());
                body.put("ingestLagMs",         snap.ingestLagMs());
                body.put("creditsRemaining",    snap.creditsRemaining());

                byte[] bytes = mapper.writeValueAsBytes(body);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (Exception e) {
                log.error("Health handler error", e);
                byte[] err = "{\"status\":\"error\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(err); }
            }
        });
        // Single-thread executor — health reads are fast and low-volume
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "health-server");
            t.setDaemon(true);  // don't block JVM exit
            return t;
        }));
        server.start();
        log.info("Health server listening on http://0.0.0.0:{}/health", port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }
}
