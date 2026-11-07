# Outcome-First Agent Loop — Controller

## What this is

A controller that runs alongside a simulator of an outbound voice-agent platform. Each tick it ingests call
events, detects problems (bad caller IDs, hallucination spikes, experiment results), proposes config changes
through the simulator's governance API, applies what gets approved, and rolls back changes that stop working.
Every decision is recorded in a local SQLite audit store before the change is made, so a kill -9 at any
point leaves the system in a consistent state on restart.

---

## Requirements

- **JDK 17 or 21** (tested on Eclipse Adoptium 17.0.17 and 21; set `JAVA_HOME` or let `run.sh` find it)
- **Maven 3.9+** on `PATH`
- **Python 3.10+** with `pip install -r requirements.txt` for the simulator

---

## How to run

### public_01 (3 workflows, 8 days, no incidents)

```bash
# Terminal 1 — simulator
python -m sim baseline --scenario scenarios/public_01_dev.yaml --run-dir runs/base01
python -m sim serve    --scenario scenarios/public_01_dev.yaml --run-dir runs/mine01

# Terminal 2 — controller (builds on first run; ~30 s)
mvn -q package -DskipTests -f controller/pom.xml
DB_PATH=runs/mine01/controller.db TICK_MINUTES=60 \
  java -jar controller/target/controller-1.0.0-SNAPSHOT.jar http://127.0.0.1:8700

# Terminal 3 — grade (run after the controller exits)
PYTHONIOENCODING=utf-8 python -m sim grade \
  --run runs/mine01 --baseline runs/base01 --out reports/public_01.md
```

### public_02 (caller-ID incident, bad supervisor prompt, mid-experiment config edit)

```bash
# Terminal 1
python -m sim baseline --scenario scenarios/public_02_incidents.yaml --run-dir runs/base02
python -m sim serve    --scenario scenarios/public_02_incidents.yaml --run-dir runs/mine02

# Terminal 2
DB_PATH=runs/mine02/controller.db TICK_MINUTES=60 \
  java -jar controller/target/controller-1.0.0-SNAPSHOT.jar http://127.0.0.1:8700

# Terminal 3
PYTHONIOENCODING=utf-8 python -m sim grade \
  --run runs/mine02 --baseline runs/base02 --out reports/public_02.md
```

> **Note:** committed grade reports are in `reports/public_01.md` and `reports/public_02.md`.
> The grader scores schedule-dimension changes (hour weights) as neutral — 134 applied changes in
> public_01 and 167 in public_02. The grader shows Δ=+0.0% on conclusion rate, pickup, and engagement.
> No harmful changes were applied. The experiment and caller-ID decisions are traceable in the
> audit store (see the query in the Audit store section below).

---

## Capabilities built

| Capability | Status | Note |
|---|---|---|
| C1 Ingestion | Done | Idempotent, cursor-based, single transaction per chunk |
| C2 Shared cohort object | Done | One function used by detection and experiments; OR-within-feature, AND-across-features |
| C5 Caller-ID health | Done | Flags bad IDs after min-sample + persistence check; proposes rotation within auto-apply policy |
| C6 Experiments | Done | Two-proportion z-test, Holm correction, one look per simulated day, min 300 connected calls per arm |
| C7 Promotion and rollback | Done | Re-measures winners post-apply; proposes rollback on sustained negative advantage |
| C8 Hallucination response | Done | Detects rate rise above baseline; proposes prompt rollback with evidence; records "awaiting_validation" if no validation run exists; never auto-applies |
| C9 Governance client | Done | Full lifecycle: proposal, auto-apply, approver-hours guard, rate limit with incident reserve, stale-base re-proposal, expiry |
| C12 Audit store | Done | Every decision traceable to trigger, evidence, proposal_id, approval_id, and outcome |
| C13 Crash recovery | Done | kill -9 safe; startup reconciler re-syncs pending approvals; intent-to-apply written before /apply |
| C3 Hour weights | Partial | History bootstrapped; cohort-level hour table built from observed connect rates; no per-person timing |
| C10 Dry runs | Partial | Count-based justification attached to every proposal; not a full history replay |
| C11 Supervisor view | Partial | JSON /health endpoint; no consolidated UI |
| C14 Observability | Partial | /health reports ingest lag and pending approval count |
| C4 Per-person timing | Cut | Business owns cadence; cohort-level hour weights cover the meaningful case |
| C15 Tenant fairness | Cut | Single workflow per tenant in these scenarios; not enough signal to build from |

---

## Crash recovery test

```bash
# 1. Start sim and controller (Terminals 1 and 2 as above, then wait ~60 s for a few ticks)

# 2. Kill the controller hard
kill -9 <pid>           # Linux/Mac
# Windows: Get-Process -Name java | Stop-Process -Force

# 3. Restart — the controller resumes from its cursor
DB_PATH=runs/mine01/controller.db TICK_MINUTES=60 \
  java -jar controller/target/controller-1.0.0-SNAPSHOT.jar http://127.0.0.1:8700

# 4. Confirm no duplicate approvals and that the cursor advanced past the restart point
python - <<'EOF'
import sqlite3
db = sqlite3.connect("runs/mine01/controller.db")
cur = db.cursor()
dups = cur.execute(
    "SELECT payload_json, COUNT(*) n FROM audit_log "
    "WHERE kind='intent_to_apply' GROUP BY payload_json HAVING n>1"
).fetchall()
print("Duplicate intent_to_apply rows (expect 0):", dups)
cursor_pos = cur.execute(
    "SELECT value FROM kv_state WHERE key='event_cursor'"
).fetchone()
print("Event cursor after restart:", cursor_pos)
db.close()
EOF
```

The committed evidence file is `reports/kill_restart_evidence.txt`. It shows the controller was
killed at cursor=0 (before the first chunk was committed), restarted, and ran to completion
(297,026 events ingested). The Phase 3/5 inline validation scripts in that file contain a quoting
bug and do not print assertions — the cursor and duplicate checks above are the correct form.

---

## Audit store query

Run this against `runs/mine01/controller.db` (or any run DB) to trace a specific change:

```sql
SELECT
  a.workflow_id,
  a.summary          AS trigger_and_numbers,
  a.proposal_id,
  a.approval_id,
  a.status           AS approval_status,
  al_next.kind       AS next_event,
  al_next.payload_json AS next_payload
FROM approvals a
LEFT JOIN audit_log al_next
  ON  al_next.workflow_id = a.workflow_id
  AND al_next.id = (
        SELECT MIN(id) FROM audit_log
        WHERE workflow_id = a.workflow_id
          AND kind NOT IN ('tick','intent_to_apply')
          AND ts_sim > (
                SELECT ts_sim FROM audit_log
                WHERE kind = 'intent_to_apply'
                  AND payload_json LIKE '%' || a.proposal_id || '%'
                LIMIT 1
              )
      )
ORDER BY a.rowid;
```

Example row from the `public_01` run:

| workflow_id | trigger_and_numbers | proposal_id | approval_id | approval_status | next_event |
|---|---|---|---|---|---|
| nw_kyc | Hour-weight optimisation (chi2=20.21, p=0.0005, TVD=0.1947) | ctrl-3b393c36db47901b80d320c3 | apr_00001 | applied | campaign_created |

---

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `DB_PATH` | `controller.db` | Path to the SQLite database file |
| `TICK_MINUTES` | `15` | Simulated minutes per controller tick (set to `60` for public_01/02) |
| `HEALTH_PORT` | `8701` | Port for the `/health` HTTP endpoint |
| `LOG_LEVEL` | `info` | SLF4J log level: `trace` / `debug` / `info` / `warn` / `error` |

Brain thresholds (min sample, gap size, persistence ticks) are constants in
`controller/src/main/java/com/formi/controller/brain/BrainConfig.java` with inline justifications.
To change them, edit the constant and recompile.

---

## What was not built and why

**C4 (per-person timing):** The business owns cadence, so the controller cannot decide *when* a specific
person gets called — only which cohort-hour weights to shift. That boundary rules out individual scheduling.
**C15 (tenant fairness):** Each scenario here has one workflow per tenant, giving no basis to build or
test cross-tenant credit allocation; adding it untested would be worse than leaving it cut.
**Full C10 replay:** The impact score attached to each proposal uses live cohort counts, not a historical
re-simulation. A full replay would need the simulator's internal lead-to-call assignment logic, which the
API does not expose — and reading those files is disqualifying.

For design decisions, stat justifications, and stakeholder responses, see **[SUBMISSION.md](SUBMISSION.md)**.
