# Outcome-First Voice Agent Controller

A production-grade, crash-resilient Java controller for the closed-loop simulated outbound voice-agent platform (**Formi / Agentic Universe**).

---

## Architecture & Day 1 Foundation Overview

The controller operates as an always-on control loop that communicates with the simulator **strictly over HTTP**. It builds a local source of truth in SQLite, ingests events idempotently, respects human governance boundaries, and guarantees crash safety across all failure modes.

```
                  ┌──────────────────────────────────────────────┐
                  │              Closed-Loop Simulator           │
                  │              (HTTP API on :8700)             │
                  └──────▲────────────────┬───────────────┬──────┘
                         │                │               │
POST /sim/advance        │                │ GET /events   │ POST /approvals
                         │                │   /chunks/{n} │ POST /workflows/{id}/apply
                         │                │               │
                  ┌──────┴────────────────▼───────────────▼──────┐
                  │                 Java Controller              │
                  │                                              │
                  │  ┌──────────────┐     ┌──────────────────┐   │
                  │  │   MainLoop   │────▶│    SimClient     │   │
                  │  └──────┬───────┘     │ (HTTP/1.1 retry) │   │
                  │         │             └──────────────────┘   │
                  │         ├──────────────────────────┐         │
                  │         ▼                          ▼         │
                  │  ┌──────────────┐         ┌────────────────┐ │
                  │  │   Ingester   │         │   Governance   │ │
                  │  │ (idempotent, │         │    Service     │ │
                  │  │  streamed)   │         │ (state machine)│ │
                  │  └──────┬───────┘         └────────┬───────┘ │
                  │         │                          │         │
                  │         ▼                          ▼         │
                  │  ┌─────────────────────────────────────────┐ │
                  │  │            SQLite Store (WAL)           │ │
                  │  │    calls | leads | approvals | audit    │ │
                  │  └─────────────────────────────────────────┘ │
                  └───────────────────────┬──────────────────────┘
                                          │
                                   GET /health (:8701)
                                          ▼
                                   [Health Monitor]
```

---

## Key Guarantees & Design Principles

### 1. Ingestion Idempotency & Backlog Safety
- **Single Transaction Per Chunk**: Ingests calls, leads, config changes, approval events, and updates the `event_cursor` in **one atomic database transaction**.
- **Crash Rollback**: An abrupt kill (`kill -9`) mid-chunk rolls back the transaction entirely; on restart, the cursor remains at the previous chunk and re-processes from that boundary.
- **Deduplication Guard**: `INSERT OR IGNORE` on `calls.event_id` and `leads.lead_id` ensures replayed events are harmless.
- **NDJSON Streaming**: Reads chunk lines incrementally via `BufferedReader` so memory footprint stays minimal even under full load (~850k events/day).

### 2. Strict Governance Boundary
- **Deterministic Proposal ID**: Computed as `ctrl-` + first 24 chars of `SHA-256(workflow_id : base_version : canonical_config_json)`. If a proposal was sent before a crash, retrying with the same intent produces the identical ID.
- **Conflict Handling**: On `409 duplicate_proposal_id`, queries `GET /approvals` and adopts the existing `approval_id`.
- **Approver Hours & Rate Limits**: Proposes only within `approver_hours` (`[10, 19]` in simulated time), keeping at least 1 request slot in reserve for urgent incidents.
- **Two-Phase Intent Journaling**: Logs `intent_to_apply` in `audit_log` prior to calling `POST /workflows/{id}/apply`. On failure due to `409 stale_base_version`, flags the proposal as stale without double-applying.

### 3. Storage & Auditability
- **SQLite in WAL Mode (`PRAGMA journal_mode=WAL`)**: Allows concurrent reads from the background `/health` thread without blocking the main write loop.
- **Queryable Decision Store**: Append-only `audit_log` tracking ticks, supervisor edits, proposal submissions, approval outcomes, and application results.
- **Separate KPI Isolation**: Pickup rate and engagement rate are tracked in separate database fields and never blended.

---

## Project Structure

```
.
├── Makefile
├── README.md
├── SUBMISSION_TEMPLATE.md
├── requirements.txt              # Simulator Python dependencies
├── run.sh                        # Top-level executable wrapper
├── controller/
│   ├── pom.xml                   # Maven project config (Java 17/21, shaded jar)
│   ├── run.sh                    # Controller execution script
│   ├── ASSUMPTIONS.md            # Running list of documented API assumptions
│   └── src/
│       ├── main/java/com/formi/controller/
│       │   ├── Main.java         # Service bootstrap & shutdown hook
│       │   ├── Config.java       # Immutable environment configuration
│       │   ├── client/           # Typed simulator HTTP client & custom exceptions
│       │   │   ├── SimClient.java
│       │   │   ├── ApiException.java
│       │   │   └── model/        # Typed JSON models (Jackson)
│       │   ├── db/               # SQLite connection & JDBC store
│       │   │   ├── Database.java
│       │   │   └── Store.java
│       │   ├── ingestion/        # Idempotent chunk ingestion
│       │   │   └── Ingester.java
│       │   ├── governance/       # Governance state machine & rate limiter
│       │   │   └── GovernanceService.java
│       │   ├── health/           # Minimal zero-dependency /health server
│       │   │   ├── HealthServer.java
│       │   │   └── HealthState.java
│       │   └── loop/             # Main simulation control loop
│       │       └── MainLoop.java
│       └── test/java/com/formi/controller/
│           ├── ingestion/IngesterIdempotencyTest.java
│           └── governance/GovernanceServiceTest.java
├── docs/
│   └── SIMULATOR.md              # Outside world specification
├── reports/
│   └── public_01.md              # Grader report from end-to-end run
├── scenarios/                    # Scenario definitions (dev, incidents, load)
├── sim/                          # Closed-loop simulator engine
└── tests/                        # Simulator test suite
```

---

## Quickstart

### Prerequisites
- **JDK 17+** (or JDK 21+)
- **Apache Maven 3.9+**
- **Python 3.10+** (for running the simulator)

### 1. Install Simulator Dependencies
```bash
pip install -r requirements.txt
```

### 2. Run Controller Tests
```bash
cd controller
mvn test
cd ..
```

### 3. Run public_01 End-to-End

**Terminal 1 — Start the Simulator:**
```bash
python -m sim serve --scenario scenarios/public_01_dev.yaml --run-dir runs/mine01 --port 8700
```

**Terminal 2 — Run the Controller:**
```bash
./run.sh http://127.0.0.1:8700
```

The controller will:
1. Bootstrap historical chunk data prior to D0.
2. Advance through the 8 simulated days.
3. Submit a smoke-test proposal within approver hours, receive approval, and apply it.
4. Exit cleanly on scenario completion after ingesting all 297,000+ events.

### 4. Verify & Grade
```bash
python -m sim baseline --scenario scenarios/public_01_dev.yaml --run-dir runs/base01
python -m sim grade --run runs/mine01 --baseline runs/base01 --out reports/public_01.md
```

---

## Configuration Reference

Set via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `DB_PATH` | `controller.db` | Path to the SQLite database file |
| `TICK_MINUTES` | `15` | Simulated minutes to advance per tick |
| `HEALTH_PORT` | `8701` | HTTP port for the `/health` endpoint |
| `LOG_LEVEL` | `info` | SLF4J log verbosity (`trace`, `debug`, `info`, `warn`, `error`) |
| `DEMO_PROPOSAL` | `false` | When `true`, submits one harmless proposal on Day 1 to exercise the governance cycle |
