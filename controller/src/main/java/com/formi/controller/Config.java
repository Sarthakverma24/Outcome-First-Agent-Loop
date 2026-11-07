package com.formi.controller;

/**
 * All runtime configuration, loaded once at startup from environment variables.
 *
 * Design: a plain Java record — immutable, cheap to pass around, no Spring/DI.
 * Every field has a safe default so the only *required* input is the simulator URL
 * (provided as args[0], not an env var, so the caller in run.sh is unambiguous).
 */
public record Config(
        String simUrl,          // e.g. http://127.0.0.1:8700
        String dbPath,          // path to SQLite file
        int    tickMinutes,     // simulated minutes per advance call
        int    healthPort,      // port for /health HTTP endpoint
        String logLevel,        // passed to SLF4J simple logger via -D in run.sh
        boolean demoProposal    // if true, submit one harmless proposal to test the approval lifecycle
) {

    /**
     * Build Config from environment variables.
     *
     * @param simUrl  the simulator base URL, taken from args[0] by Main
     */
    public static Config fromEnv(String simUrl) {
        return new Config(
                simUrl,
                env("DB_PATH",       "controller.db"),
                envInt("TICK_MINUTES", 15),
                envInt("HEALTH_PORT",  8701),
                env("LOG_LEVEL",     "info"),
                "true".equalsIgnoreCase(env("DEMO_PROPOSAL", "false"))
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String env(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : defaultValue;
    }

    private static int envInt(String key, int defaultValue) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Environment variable " + key + " must be an integer, got: " + v);
        }
    }
}
