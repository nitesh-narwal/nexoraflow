package me.niteshh.nexoraflow.common;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Empties every business and ground-truth table and resets ID counters. Used by the generator (to start
 * from a clean, reproducible state) and by integration tests. Flyway's history table is never touched.
 */
@Service
public class DatabaseResetService {

    /** Every application table, children first (TRUNCATE ... CASCADE makes the order irrelevant, but it documents it). */
    public static final List<String> TABLES = List.of(
            "ground_truth.label", "report_snapshot", "generation_run",
            "reimbursement_attempt", "reimbursement", "approval_step", "decision_trace", "audit_event", "detected_anomaly",
            "submission_record", "expense_line", "expense_claim", "receipt",
            "leave_balance_transaction", "leave_request", "leave_balance",
            "employee_policy_assignment", "budget", "approval_rule", "expense_policy", "leave_policy",
            "holiday", "holiday_calendar", "employee_cost_center", "cost_center", "employee", "department",
            "country", "currency", "id_counter");

    private final JdbcTemplate jdbc;

    public DatabaseResetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void resetAll() {
        jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES) + " RESTART IDENTITY CASCADE");
    }
}
