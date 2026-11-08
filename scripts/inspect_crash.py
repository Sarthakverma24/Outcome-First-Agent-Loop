import sqlite3
import sys

db_path = sys.argv[1] if len(sys.argv) > 1 else "runs/test_kill/controller.db"
conn = sqlite3.connect(db_path)
c = conn.cursor()

cursor_row = c.execute("SELECT value FROM kv_state WHERE key='event_cursor'").fetchone()
cursor_val = cursor_row[0] if cursor_row else "None"
print(f"Event cursor at crash: {cursor_val}")

calls_count = c.execute("SELECT COUNT(*) FROM calls").fetchone()[0]
print(f"Calls ingested at crash: {calls_count}")

approvals = c.execute("SELECT approval_id, proposal_id, status FROM approvals").fetchall()
print(f"Approvals recorded at crash ({len(approvals)}):")
for a in approvals:
    print(f"  approval_id={a[0]} proposal_id={a[1]} status={a[2]}")

intents = c.execute("SELECT COUNT(*) FROM audit_log WHERE kind='intent_to_apply'").fetchone()[0]
print(f"Intent-to-apply recorded at crash: {intents}")

applies = c.execute("SELECT COUNT(*) FROM audit_log WHERE kind='applied'").fetchone()[0]
print(f"Applies recorded at crash: {applies}")

audit_count = c.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]
print(f"Total audit log rows at crash: {audit_count}")

experiments = c.execute("SELECT experiment_id, status, dimension, control_value FROM experiments").fetchall()
print(f"Experiments at crash ({len(experiments)}):")
for exp in experiments:
    print(f"  exp_id={exp[0]} status={exp[1]} dim={exp[2]} control={exp[3]}")

integrity = c.execute("PRAGMA integrity_check").fetchone()[0]
print(f"SQLite PRAGMA integrity_check: {integrity}")

conn.close()
