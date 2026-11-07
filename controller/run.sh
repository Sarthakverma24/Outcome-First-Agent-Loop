#!/usr/bin/env bash
# run.sh — build (if needed) and launch the Formi controller.
#
# Requirements:
#   - JDK 17+ or JDK 21+ (tested with Eclipse Adoptium 17.0.17 on Linux / Windows)
#   - Apache Maven 3.9+ on PATH
#   - Internet access for first build (downloads deps to ~/.m2)
#
# Usage:
#   ./run.sh <SIM_URL>           # e.g. ./run.sh http://127.0.0.1:8700
#
# Optional environment variables (all have safe defaults):
#   DB_PATH          Path to the SQLite database file  (default: ./controller.db)
#   TICK_MINUTES     Simulated minutes per advance call (default: 15)
#   HEALTH_PORT      Port for /health HTTP endpoint     (default: 8701)
#   LOG_LEVEL        SLF4J log level: trace/debug/info/warn/error (default: info)
#   DEMO_PROPOSAL    Set to "true" to submit one harmless test proposal (default: false)

set -euo pipefail

SIM_URL="${1:?Usage: ./run.sh <SIM_URL>}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/target/controller-1.0.0-SNAPSHOT.jar"

# Determine Java binary (prioritize JAVA_HOME if set, then known JDK 17+ paths, then system java)
JAVA_BIN="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot/bin/java.exe" ]; then
    JAVA_BIN="C:/Program Files/Eclipse Adoptium/jdk-17.0.17.10-hotspot/bin/java.exe"
fi

# ── Build if the jar is missing or sources are newer ──────────────────────────
if [ ! -f "$JAR" ] || [ -n "$(find "$SCRIPT_DIR/src" -newer "$JAR" -name "*.java" 2>/dev/null || true)" ]; then
    echo "[run.sh] Building fat jar with Maven…" >&2
    (cd "$SCRIPT_DIR" && mvn -q package -DskipTests)
fi

# ── Launch ────────────────────────────────────────────────────────────────────
LOG_LEVEL="${LOG_LEVEL:-info}"

exec "$JAVA_BIN" \
    -Dorg.slf4j.simpleLogger.defaultLogLevel="$LOG_LEVEL" \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS" \
    -Dorg.slf4j.simpleLogger.showShortLogName=true \
    -jar "$JAR" \
    "$SIM_URL"
