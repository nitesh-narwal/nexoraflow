package me.niteshh.nexoraflow.export;

import java.util.List;

/**
 * The exported tables: file name, database table, ordering key and whether the table has circular foreign keys
 * that the SQL seed must resolve with trailing UPDATE statements. Tables are listed in foreign-key dependency order.
 */
public final class TableCatalog {

    public record ExportTable(String fileName, String table, String orderBy, List<String> deferredColumns) {
        public ExportTable(String fileName, String table, String orderBy) {
            this(fileName, table, orderBy, List.of());
        }

        public String primaryKeyColumn() {
            return orderBy.split(",")[0].trim();
        }
    }

    private TableCatalog() {
    }

    public static final List<ExportTable> TABLES = List.of(
            new ExportTable("id_counters", "id_counter", "counter_key"),
            new ExportTable("currencies", "currency", "currency_code"),
            new ExportTable("countries", "country", "country_code"),
            new ExportTable("departments", "department", "department_id", List.of("head_employee_id")),
            new ExportTable("employees", "employee", "employee_id", List.of("cost_center_id")),
            new ExportTable("cost_centers", "cost_center", "cost_center_id"),
            new ExportTable("employee_cost_centers", "employee_cost_center", "assignment_id"),
            new ExportTable("holiday_calendars", "holiday_calendar", "calendar_id"),
            new ExportTable("holidays", "holiday", "holiday_id"),
            new ExportTable("leave_policies", "leave_policy", "leave_policy_id"),
            new ExportTable("expense_policies", "expense_policy", "expense_policy_id"),
            new ExportTable("approval_rules", "approval_rule", "approval_rule_id"),
            new ExportTable("budgets", "budget", "budget_id"),
            new ExportTable("employee_policy_assignments", "employee_policy_assignment", "assignment_id"),
            new ExportTable("leave_balances", "leave_balance", "leave_balance_id"),
            new ExportTable("leave_requests", "leave_request", "leave_request_id"),
            new ExportTable("leave_balance_transactions", "leave_balance_transaction", "txn_id"),
            new ExportTable("receipts", "receipt", "receipt_id"),
            new ExportTable("expense_claims", "expense_claim", "expense_claim_id"),
            new ExportTable("expense_lines", "expense_line", "expense_line_id"),
            new ExportTable("submission_records", "submission_record", "submission_id"),
            new ExportTable("approval_steps", "approval_step", "approval_step_id"),
            new ExportTable("reimbursements", "reimbursement", "reimbursement_id"),
            new ExportTable("reimbursement_attempts", "reimbursement_attempt", "attempt_id"),
            new ExportTable("detected_anomalies", "detected_anomaly", "anomaly_id"),
            new ExportTable("decision_traces", "decision_trace", "trace_id"),
            new ExportTable("audit_events", "audit_event", "event_id"));
}
