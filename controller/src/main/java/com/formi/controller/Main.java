package com.formi.controller;

import com.formi.controller.client.SimClient;
import com.formi.controller.brain.Brain;
import com.formi.controller.db.Database;
import com.formi.controller.db.Store;
import com.formi.controller.governance.GovernanceService;
import com.formi.controller.health.HealthServer;
import com.formi.controller.health.HealthState;
import com.formi.controller.ingestion.Ingester;
import com.formi.controller.loop.MainLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Wires all components together and starts the control loop.
 *
 * Startup order matters:
 *   1. Config — env vars, fail fast if required args are missing.
 *   2. Database — open file, apply schema migrations.
 *   3. SimClient — verify simulator is reachable before we do anything.
 *   4. HealthServer — start early so a process monitor sees /health immediately.
 *   5. MainLoop — runs until scenario ends or SIGTERM.
 *
 * SIGTERM / graceful shutdown:
 *   The shutdown hook sets a stop flag on the main loop. The loop checks the flag
 *   after each tick (not mid-tick) so the DB is always left in a consistent state.
 *   A kill -9 is safe because the DB transaction boundary is inside Ingester —
 *   any in-progress chunk is rolled back and re-processed on the next start.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {

        // ── 1. Config ──────────────────────────────────────────────────────────
        if (args.length < 1) {
            System.err.println("Usage: controller <SIM_URL>");
            System.err.println("  e.g. java -jar controller.jar http://127.0.0.1:8700");
            System.exit(1);
        }
        Config cfg = Config.fromEnv(args[0]);
        log.info("Starting controller: simUrl={}, db={}, tick={}min, health=:{}",
                cfg.simUrl(), cfg.dbPath(), cfg.tickMinutes(), cfg.healthPort());

        // ── 2. Database ────────────────────────────────────────────────────────
        Database db = new Database(cfg.dbPath());
        db.init();
        Store store = new Store(db.getConnection());

        // ── 3. Simulator client + health check ─────────────────────────────────
        SimClient client = new SimClient(cfg.simUrl());
        try {
            client.health();
        } catch (Exception e) {
            log.error("Cannot reach simulator at {}: {}", cfg.simUrl(), e.getMessage());
            System.exit(1);
        }

        // ── 4. Health server ───────────────────────────────────────────────────
        HealthState healthState = new HealthState();
        HealthServer healthServer = new HealthServer(cfg.healthPort(), healthState);
        healthServer.start();

        // ── 5. Build services ─────────────────────────────────────────────────
        Ingester ingester = new Ingester(client, store, db.getConnection());
        GovernanceService governance = new GovernanceService(client, store);
        Brain brain = new Brain(db.getConnection(), store, client, governance);
        MainLoop loop = new MainLoop(cfg, client, store, ingester, governance, healthState, brain);

        // ── SIGTERM handler ───────────────────────────────────────────────────
        // Requests a clean stop after the current tick completes.
        // Note: kill -9 bypasses this — that's safe by design (see class javadoc).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received — requesting loop stop after current tick…");
            loop.requestStop();
            // Give the loop up to 30 s to finish its current tick cleanly
            try { Thread.sleep(30_000); } catch (InterruptedException ignored) {}
        }, "shutdown-hook"));

        // ── 6. Run ────────────────────────────────────────────────────────────
        try {
            loop.run();
        } finally {
            log.info("Closing database…");
            db.close();
            healthServer.stop();
            log.info("Controller exited cleanly.");
        }
    }
}
