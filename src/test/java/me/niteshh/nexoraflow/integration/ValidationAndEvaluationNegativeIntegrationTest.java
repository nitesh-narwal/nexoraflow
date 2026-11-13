package me.niteshh.nexoraflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.evaluation.EvaluationReport;
import me.niteshh.nexoraflow.evaluation.EvaluationService;
import me.niteshh.nexoraflow.support.AbstractGeneratedDatasetTest;
import me.niteshh.nexoraflow.validation.ValidationReport;
import me.niteshh.nexoraflow.validation.ValidationService;

/**
 * Proves the validator and the evaluator are not vacuous: on a clean generated dataset they pass, and every kind of
 * deliberate corruption is reported with the affected records. Each test mutates inside a rolled-back transaction.
 */
class ValidationAndEvaluationNegativeIntegrationTest extends AbstractGeneratedDatasetTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ValidationService validation;
    @Autowired EvaluationService evaluation;

    private void bypassTriggers() {
        jdbc.execute("SET LOCAL session_replication_role = replica"); // lets the test write the corrupt rows the database normally refuses
    }

    private ValidationReport.CheckResult check(ValidationReport report, String id) {
        return report.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theGeneratedDatasetPassesEveryCheckAndMatchesTheGroundTruth() {
        ValidationReport report = validation.run();
        assertThat(report.summary().failed()).isZero();
        assertThat(report.summary().allPassed()).isTrue();
        assertThat(check(report, "DQ-08").status()).isEqualTo("INFO"); // deliberately invalid submissions were preserved, not corrupted
        EvaluationReport eval = evaluation.evaluate();
        assertThat(eval.mismatches()).isEmpty();
        assertThat(eval.anomalyDetection().recall()).isEqualTo(1.0);
        assertThat(eval.anomalyDetection().precision()).isEqualTo(1.0);
        assertThat(eval.totals().unlabelledRequests()).isZero();
        assertThat(eval.controls().foreignKeyValidationFailures()).isZero();
        assertThat(eval.controls().duplicateBusinessIdentifiers()).isZero();
    }

    @Test
    @Transactional
    void theEvaluationReportsForeignKeyFailuresAndDuplicateIdentifiersAsIntegrityCounters() {
        bypassTriggers();
        // an orphan foreign key (the database normally refuses it)
        jdbc.update("INSERT INTO leave_request (leave_request_id, employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, request_status, created_at, updated_at) "
                + "VALUES ('LVE-2026-999001', 'EMP-BOGUS', 'LPOL-SICK', '2026-11-02', '2026-11-03', 2, '2026-10-22', now(), 'SUBMITTED', now(), now())");
        // a duplicate business identifier (the primary key is dropped inside this rolled-back transaction to make it possible)
        jdbc.execute("ALTER TABLE department DROP CONSTRAINT department_pkey CASCADE");
        jdbc.update("INSERT INTO department (department_id, department_name, status, created_at, updated_at) "
                + "VALUES ((SELECT min(department_id) FROM department), 'Duplicate identifier', 'ACTIVE', now(), now())");

        EvaluationReport report = evaluation.evaluate();
        assertThat(report.controls().foreignKeyValidationFailures()).isGreaterThanOrEqualTo(1);
        assertThat(report.controls().duplicateBusinessIdentifiers()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Transactional
    void everyDeliberateCorruptionIsReportedWithTheAffectedRecords() {
        bypassTriggers();
        String employee = jdbc.queryForObject("SELECT employee_id FROM employee WHERE manager_id IS NOT NULL ORDER BY employee_id LIMIT 1", String.class);
        String approvedClaim = jdbc.queryForObject("SELECT expense_claim_id FROM expense_claim WHERE claim_status = 'APPROVED' ORDER BY 1 LIMIT 1", String.class);
        String approvedLeave = jdbc.queryForObject("SELECT leave_request_id FROM leave_request WHERE request_status = 'APPROVED' ORDER BY 1 LIMIT 1", String.class);
        String pendingLeave = jdbc.queryForObject("SELECT leave_request_id FROM leave_request WHERE request_status = 'PENDING_APPROVAL' ORDER BY 1 LIMIT 1", String.class);
        String line = jdbc.queryForObject("SELECT expense_line_id FROM expense_line WHERE line_status = 'VALID' ORDER BY 1 LIMIT 1", String.class);

        // DI-02: orphan foreign key
        jdbc.update("INSERT INTO leave_request (leave_request_id, employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, request_status, created_at, updated_at) "
                + "VALUES ('LVE-2026-999001', 'EMP-BOGUS', 'LPOL-SICK', '2026-11-02', '2026-11-03', 2, '2026-10-22', now(), 'SUBMITTED', now(), now())");
        // BI-01: claim total no longer equals the sum of its lines
        jdbc.update("UPDATE expense_claim SET total_amount = total_amount + 5 WHERE expense_claim_id = ?", approvedClaim);
        // BI-02: balance component drifted from the ledger
        jdbc.update("UPDATE leave_balance SET used_balance = used_balance + 1 WHERE leave_balance_id = (SELECT leave_balance_id FROM leave_balance_transaction WHERE leave_request_id = ? LIMIT 1)", approvedLeave);
        // BI-03/04: a pending request marked APPROVED without consuming balance
        jdbc.update("UPDATE leave_request SET request_status = 'APPROVED' WHERE leave_request_id = ?", pendingLeave);
        // BI-07: approved claim loses its reimbursement
        jdbc.update("DELETE FROM reimbursement WHERE expense_claim_id = ?", approvedClaim);
        // DQ-01: hierarchy cycle
        jdbc.update("UPDATE employee SET manager_id = ? WHERE manager_id IS NULL", employee);
        // DQ-02: negative amount on a VALID line
        jdbc.update("UPDATE expense_line SET amount = -10, line_status = 'INVALID', validation_reason = 'SOMETHING_ELSE' WHERE expense_line_id = ?", line);
        // DQ-06: overlapping approved leave
        String owner = jdbc.queryForObject("SELECT employee_id FROM leave_request WHERE leave_request_id = ?", String.class, approvedLeave);
        jdbc.update("INSERT INTO leave_request (leave_request_id, employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, request_status, created_at, updated_at) "
                + "SELECT 'LVE-2026-999002', employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, 'APPROVED', now(), now() FROM leave_request WHERE leave_request_id = ?", approvedLeave);
        // BI-10: audit trail with an impossible transition
        jdbc.update("INSERT INTO audit_event (event_id, event_category, entity_type, entity_id, event_type, previous_state, new_state, actor, occurred_at) "
                + "VALUES ('EVT-99999999', 'WORKFLOW', 'LEAVE_REQUEST', ?, 'STATUS_CHANGED', 'REJECTED', 'APPROVED', 'SYSTEM', now() + interval '1 day')", approvedLeave);

        ValidationReport report = validation.run();
        assertThat(report.summary().allPassed()).isFalse();
        List<String> failed = report.checks().stream().filter(c -> c.status().equals("FAIL")).map(ValidationReport.CheckResult::id).toList();
        assertThat(failed).contains("DI-02", "BI-01", "BI-02", "BI-03", "BI-07", "DQ-01", "DQ-02", "DQ-06", "BI-10");
        assertThat(check(report, "DI-02").findings()).anyMatch(f -> f.entityId().equals("LVE-2026-999001"));
        assertThat(check(report, "BI-01").findings()).anyMatch(f -> f.entityId().equals(approvedClaim) && f.message().contains("sum of lines"));
        assertThat(check(report, "BI-03").findings()).anyMatch(f -> f.entityId().equals(pendingLeave));
        assertThat(check(report, "DQ-02").findings()).anyMatch(f -> f.entityId().equals(line));
        assertThat(check(report, "DQ-06").findings()).anyMatch(f -> f.message().contains("overlaps approved request"));
        assertThat(check(report, "BI-07").findings()).anyMatch(f -> f.entityId().equals(approvedClaim));
        assertThat(owner).isNotNull();
    }

    @Test
    @Transactional
    void evaluationNoticesWrongDecisionsMissedAnomaliesAndFalsePositives() {
        bypassTriggers();
        EvaluationReport clean = evaluation.evaluate();
        assertThat(clean.mismatches()).isEmpty();

        // 1. a valid claim is wrongly flagged by the "engine" -> false positive and wrong outcome
        String valid = jdbc.queryForObject("""
                SELECT l.entity_id FROM ground_truth.label l JOIN expense_claim c ON c.expense_claim_id = l.entity_id
                WHERE l.entity_type = 'EXPENSE_CLAIM' AND NOT l.is_anomaly AND c.claim_status = 'APPROVED' ORDER BY 1 LIMIT 1""", String.class);
        jdbc.update("UPDATE expense_claim SET claim_status = 'FLAGGED', decision_reason = 'RECEIPT_MISSING' WHERE expense_claim_id = ?", valid);
        // 2. an anomaly goes undetected: its reason code disappears
        String anomaly = jdbc.queryForObject("""
                SELECT l.entity_id FROM ground_truth.label l WHERE l.entity_type = 'EXPENSE_CLAIM' AND l.is_anomaly
                  AND l.evidence_type = 'DECISION_REASON' ORDER BY 1 LIMIT 1""", String.class);
        jdbc.update("UPDATE expense_claim SET decision_reason = NULL WHERE expense_claim_id = ?", anomaly);
        // 3. a wrong approval path
        String pathClaim = jdbc.queryForObject("""
                SELECT l.entity_id FROM ground_truth.label l WHERE l.entity_type = 'EXPENSE_CLAIM' AND l.expected_approval_path IS NOT NULL
                  AND l.entity_id <> ? AND l.entity_id <> ? ORDER BY 1 LIMIT 1""", String.class, valid, anomaly);
        jdbc.update("UPDATE approval_step s SET approver_employee_id = (SELECT e.employee_id FROM employee e WHERE e.employee_id <> s.approver_employee_id ORDER BY e.employee_id DESC LIMIT 1) WHERE s.expense_claim_id = ? AND s.approval_level = 1", pathClaim);
        // 4. a reimbursement appears for a claim that must not have one
        // (checked through reimbursement accuracy below)

        EvaluationReport dirty = evaluation.evaluate();
        assertThat(dirty.mismatches()).extracting(EvaluationReport.Mismatch::entityId).contains(valid, anomaly, pathClaim);
        assertThat(dirty.anomalyDetection().falsePositives()).isGreaterThanOrEqualTo(1);
        assertThat(dirty.anomalyDetection().undetected()).isGreaterThanOrEqualTo(1);
        assertThat(dirty.anomalyDetection().recall()).isLessThan(1.0);
        assertThat(dirty.anomalyDetection().precision()).isLessThan(1.0);
        assertThat(dirty.workflowDecisions().outcomeAccuracy()).isLessThan(1.0);
        assertThat(dirty.workflowDecisions().pathAccuracy()).isLessThan(1.0);
        assertThat(dirty.mismatches()).anyMatch(m -> m.entityId().equals(valid) && m.problem().startsWith("FALSE_POSITIVE"));
        assertThat(dirty.mismatches()).anyMatch(m -> m.entityId().equals(anomaly) && m.problem().startsWith("MISSED_ANOMALY"));
        assertThat(dirty.mismatches()).anyMatch(m -> m.entityId().equals(pathClaim) && m.problem().equals("WRONG_APPROVAL_PATH"));
    }

    @Test
    void groundTruthLivesInAnIsolatedSchemaAndCoversEveryMandatoryScenario() {
        List<Map<String, Object>> issues = jdbc.queryForList("SELECT DISTINCT issue_type FROM ground_truth.label WHERE is_anomaly ORDER BY 1");
        List<String> types = issues.stream().map(m -> m.get("issue_type").toString()).toList();
        assertThat(types).contains("NEGATIVE_BALANCE", "OVERLAPPING_APPROVED_LEAVE", "OUTSIDE_EMPLOYMENT_PERIOD", "SUSPICIOUS_SPLIT_CLAIM",
                "MISSING_COST_CENTER", "SELF_APPROVAL_ATTEMPT", "DUPLICATE_REIMBURSEMENT_ATTEMPT");
        assertThat(types).contains("MISSING_RECEIPT", "DUPLICATE_RECEIPT", "INVALID_DATE_RANGE", "INACTIVE_EMPLOYEE", "INACTIVE_APPROVAL_POLICY",
                "TRANSACTION_LIMIT_EXCEEDED", "MONTHLY_BUDGET_EXHAUSTED", "UNAUTHORIZED_COST_CENTER_USAGE", "INVALID_APPROVAL_HIERARCHY",
                "MANAGER_UNAVAILABLE", "DUPLICATE_EXPENSE_SUBMISSION", "INVALID_CURRENCY", "POLICY_EFFECTIVE_DATE_MISMATCH");
        // the business tables expose no ground-truth column at all
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND column_name ILIKE '%ground%truth%'", Integer.class)).isZero();
        // every label carries the documented fields
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ground_truth.label WHERE scenario_id IS NULL OR entity_type IS NULL OR entity_id IS NULL OR issue_type IS NULL "
                + "OR expected_outcome IS NULL OR expected_reimbursement_status IS NULL OR severity IS NULL OR explanation IS NULL", Integer.class)).isZero();
    }
}
