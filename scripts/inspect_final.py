import sqlite3
import sys

db_path = sys.argv[1] if len(sys.argv) > 1 else "runs/test_kill/controller.db"
conn = sqlite3.connect(db_path)
c = conn.cursor()

cursor_row = c.execute("SELECT value FROM kv_state WHERE key='event_cursor'").fetchone()
cursor_val = cursor_row[0] if cursor_row else "None"
print(f"Event cursor after restart & completion: {cursor_val}")

calls_count = c.execute("SELECT COUNT(*) FROM calls").fetchone()[0]
print(f"Total calls ingested across both runs: {calls_count}")

# Check for duplicate proposals
dupes = c.execute("""
    SELECT proposal_id, COUNT(*) FROM approvals 
    GROUP BY proposal_id HAVING COUNT(*) > 1
""").fetchall()
print(f"Duplicate proposal IDs: {len(dupes)}")

# Check for duplicate intent_to_apply
dupe_intents = c.execute("""
    SELECT approval_id, COUNT(*) FROM (
        SELECT COALESCE(json_extract(payload_json, '$.approvalId'), json_extract(payload_json, '$.approval_id')) AS approval_id 
        FROM audit_log 
        WHERE kind='intent_to_apply'
    ) 
    WHERE approval_id IS NOT NULL
    GROUP BY approval_id HAVING COUNT(*) > 1
""").fetchall()
print(f"Duplicate intent_to_apply entries: {len(dupe_intents)}")

# Check for duplicate applied events
dupe_applies = c.execute("""
    SELECT approval_id, COUNT(*) FROM (
        SELECT COALESCE(json_extract(payload_json, '$.approvalId'), json_extract(payload_json, '$.approval_id')) AS approval_id 
        FROM audit_log 
        WHERE kind='applied'
    ) 
    WHERE approval_id IS NOT NULL
    GROUP BY approval_id HAVING COUNT(*) > 1
""").fetchall()
print(f"Duplicate apply entries: {len(dupe_applies)}")

total_applies = c.execute("SELECT COUNT(*) FROM audit_log WHERE kind='applied'").fetchone()[0]
print(f"Total successful applies: {total_applies}")

total_audit = c.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]
print(f"Total audit log rows: {total_audit}")

integrity = c.execute("PRAGMA integrity_check").fetchone()[0]
print(f"SQLite PRAGMA integrity_check: {integrity}")

conn.close()
