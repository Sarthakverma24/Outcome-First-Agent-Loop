package com.formi.controller.brain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formi.controller.db.Store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

/**
 * Generates an auditable, human-readable markdown report of all decisions,
 * proposals, and experiment outcomes from the audit_log and experiments tables.
 *
 * This report is suitable for review by an engineering lead or compliance head.
 */
public class DryRunReport {

    private final Connection conn;
    private final Store store;
    private final ObjectMapper mapper = new ObjectMapper();

    public DryRunReport(Connection conn, Store store) {
        this.conn = conn;
        this.store = store;
    }

    /**
     * Generate a markdown report for a given workflow (or all workflows if null).
     */
    public String generateReport(String workflowId) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Controller Decision & Dry-Run Report\n\n");
        sb.append("**Target Workflow:** ").append(workflowId != null ? workflowId : "All").append("\n\n");

        sb.append("## 1. Experiments Summary\n\n");
        sb.append("| Experiment ID | Workflow | Cohort | Dimension | Control | Status | Start Sim Time |\n");
        sb.append("|---|---|---|---|---|---|---|\n");

        String expSql = "SELECT experiment_id, workflow_id, cohort_id, dimension, control_value, status, start_sim_ts " +
                        "FROM experiments " + (workflowId != null ? "WHERE workflow_id=? " : "") +
                        "ORDER BY start_sim_ts DESC";

        try (PreparedStatement ps = conn.prepareStatement(expSql)) {
            if (workflowId != null) ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean hasExp = false;
                while (rs.next()) {
                    hasExp = true;
                    sb.append(String.format("| `%s` | %s | %s | %s | %s | **%s** | %s |\n",
                            rs.getString("experiment_id"),
                            rs.getString("workflow_id"),
                            rs.getString("cohort_id"),
                            rs.getString("dimension"),
                            rs.getString("control_value"),
                            rs.getString("status"),
                            rs.getString("start_sim_ts")));
                }
                if (!hasExp) {
                    sb.append("| _None_ | - | - | - | - | - | - |\n");
                }
            }
        }

        sb.append("\n## 2. Proposals & Governance Decisions\n\n");
        String appSql = "SELECT proposal_id, approval_id, workflow_id, base_version, status, summary, rationale, created_ts, decided_ts, applied_ts " +
                        "FROM approvals " + (workflowId != null ? "WHERE workflow_id=? " : "") +
                        "ORDER BY created_ts DESC";

        try (PreparedStatement ps = conn.prepareStatement(appSql)) {
            if (workflowId != null) ps.setString(1, workflowId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean hasApp = false;
                while (rs.next()) {
                    hasApp = true;
                    sb.append("### Proposal: `").append(rs.getString("proposal_id")).append("`\n\n");
                    sb.append("- **Workflow:** ").append(rs.getString("workflow_id")).append("\n");
                    sb.append("- **Approval ID:** ").append(rs.getString("approval_id")).append("\n");
                    sb.append("- **Base Version:** ").append(rs.getInt("base_version")).append("\n");
                    sb.append("- **Status:** `").append(rs.getString("status")).append("`\n");
                    sb.append("- **Created:** ").append(rs.getString("created_ts")).append("\n");
                    if (rs.getString("decided_ts") != null) {
                        sb.append("- **Decided:** ").append(rs.getString("decided_ts")).append("\n");
                    }
                    if (rs.getString("applied_ts") != null) {
                        sb.append("- **Applied:** ").append(rs.getString("applied_ts")).append("\n");
                    }
                    sb.append("- **Summary:** ").append(rs.getString("summary")).append("\n");
                    sb.append("- **Rationale:** ").append(rs.getString("rationale")).append("\n\n");
                }
                if (!hasApp) {
                    sb.append("_No proposals submitted._\n\n");
                }
            }
        }

        sb.append("## 3. Notable Audit Events\n\n");
        sb.append("| ID | Sim Timestamp | Kind | Workflow | Details |\n");
        sb.append("|---|---|---|---|---|\n");

        List<Map<String, Object>> auditEntries = store.queryAudit(workflowId, 50);
        for (Map<String, Object> entry : auditEntries) {
            String kind = (String) entry.get("kind");
            // Skip routine tick logs in this summary table
            if ("tick".equals(kind)) continue;

            sb.append(String.format("| %s | %s | `%s` | %s | `%s` |\n",
                    entry.get("id"),
                    entry.get("ts_sim"),
                    kind,
                    entry.get("workflowId"),
                    truncate((String) entry.get("payload"), 80)));
        }

        return sb.toString();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        if (text.length() <= maxLen) return text.replace("\n", " ");
        return text.substring(0, maxLen).replace("\n", " ") + "...";
    }
}
