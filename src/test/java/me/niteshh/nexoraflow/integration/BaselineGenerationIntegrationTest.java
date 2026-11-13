package me.niteshh.nexoraflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.generator.GenerationOrchestrator;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.SizeProfile;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

class BaselineGenerationIntegrationTest extends AbstractIntegrationTest {

    @Autowired GenerationOrchestrator orchestrator;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        AppClock.clearSimulatedTime();
        databaseReset.resetAll();
    }

    @Test
    void baselineLeaveAndExpenseOutcomesMatchTheIndependentGroundTruth() {
        GeneratorSettings settings = new GeneratorSettings(20261022L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));
        orchestrator.generateMaster(settings);
        var leave = orchestrator.generateLeave(settings);
        var expense = orchestrator.generateExpense(settings);
        System.out.println("LEAVE STAGE: " + leave);
        System.out.println("EXPENSE STAGE: " + expense);

        List<Map<String, Object>> leaveMismatch = jdbc.queryForList("""
                SELECT l.entity_id, l.expected_outcome, r.request_status, r.decision_reason, l.expected_approval_path,
                  (SELECT string_agg(s.approver_role || '(' || s.approver_employee_id || ')', ' > ' ORDER BY s.approval_level, s.step_sequence)
                     FROM approval_step s WHERE s.leave_request_id = r.leave_request_id AND s.approval_status NOT IN ('SKIPPED','ESCALATED')) AS actual_path
                FROM ground_truth.label l JOIN leave_request r ON r.leave_request_id = l.entity_id
                WHERE l.entity_type = 'LEAVE_REQUEST' AND (r.request_status <> l.expected_outcome)
                ORDER BY l.entity_id LIMIT 15
                """);
        List<Map<String, Object>> leavePathMismatch = jdbc.queryForList("""
                SELECT l.entity_id, l.expected_approval_path,
                  (SELECT string_agg(s.approver_role || '(' || s.approver_employee_id || ')', ' > ' ORDER BY s.approval_level, s.step_sequence)
                     FROM approval_step s WHERE s.leave_request_id = r.leave_request_id AND s.approval_status NOT IN ('SKIPPED','ESCALATED')) AS actual_path
                FROM ground_truth.label l JOIN leave_request r ON r.leave_request_id = l.entity_id
                WHERE l.entity_type = 'LEAVE_REQUEST' AND l.expected_approval_path IS NOT NULL
                  AND l.expected_approval_path IS DISTINCT FROM (SELECT string_agg(s.approver_role || '(' || s.approver_employee_id || ')', ' > ' ORDER BY s.approval_level, s.step_sequence)
                     FROM approval_step s WHERE s.leave_request_id = r.leave_request_id AND s.approval_status NOT IN ('SKIPPED','ESCALATED'))
                ORDER BY l.entity_id LIMIT 15
                """);
        List<Map<String, Object>> claimMismatch = jdbc.queryForList("""
                SELECT l.entity_id, l.expected_outcome, c.claim_status, c.decision_reason, c.total_amount
                FROM ground_truth.label l JOIN expense_claim c ON c.expense_claim_id = l.entity_id
                WHERE l.entity_type = 'EXPENSE_CLAIM' AND c.claim_status <> l.expected_outcome
                ORDER BY l.entity_id LIMIT 15
                """);
        List<Map<String, Object>> claimPathMismatch = jdbc.queryForList("""
                SELECT l.entity_id, l.expected_approval_path,
                  (SELECT string_agg(s.approver_role || '(' || s.approver_employee_id || ')', ' > ' ORDER BY s.approval_level, s.step_sequence)
                     FROM approval_step s WHERE s.expense_claim_id = c.expense_claim_id AND s.approval_status NOT IN ('SKIPPED','ESCALATED')) AS actual_path
                FROM ground_truth.label l JOIN expense_claim c ON c.expense_claim_id = l.entity_id
                WHERE l.entity_type = 'EXPENSE_CLAIM' AND l.expected_approval_path IS NOT NULL
                  AND l.expected_approval_path IS DISTINCT FROM (SELECT string_agg(s.approver_role || '(' || s.approver_employee_id || ')', ' > ' ORDER BY s.approval_level, s.step_sequence)
                     FROM approval_step s WHERE s.expense_claim_id = c.expense_claim_id AND s.approval_status NOT IN ('SKIPPED','ESCALATED'))
                ORDER BY l.entity_id LIMIT 15
                """);
        List<Map<String, Object>> reimbMismatch = jdbc.queryForList("""
                SELECT l.entity_id, l.expected_reimbursement_status, COALESCE(r.reimbursement_status, 'NONE') AS actual
                FROM ground_truth.label l JOIN expense_claim c ON c.expense_claim_id = l.entity_id
                LEFT JOIN reimbursement r ON r.expense_claim_id = c.expense_claim_id
                WHERE l.entity_type = 'EXPENSE_CLAIM' AND l.expected_reimbursement_status <> COALESCE(r.reimbursement_status, 'NONE')
                ORDER BY l.entity_id LIMIT 15
                """);
        int leaveLabels = jdbc.queryForObject("SELECT count(*) FROM ground_truth.label WHERE entity_type='LEAVE_REQUEST'", Integer.class);
        int claimLabels = jdbc.queryForObject("SELECT count(*) FROM ground_truth.label WHERE entity_type='EXPENSE_CLAIM'", Integer.class);
        System.out.println("labels: leave=" + leaveLabels + " claims=" + claimLabels);
        System.out.println("status by leave: " + jdbc.queryForList("SELECT request_status, count(*) FROM leave_request GROUP BY 1 ORDER BY 1"));
        System.out.println("status by claim: " + jdbc.queryForList("SELECT claim_status, count(*) FROM expense_claim GROUP BY 1 ORDER BY 1"));

        assertThat(leaveLabels).isGreaterThan(50);
        assertThat(claimLabels).isGreaterThan(80);
        assertThat(leaveMismatch).as("leave status mismatches").isEmpty();
        assertThat(leavePathMismatch).as("leave approval path mismatches").isEmpty();
        assertThat(claimMismatch).as("claim status mismatches").isEmpty();
        assertThat(claimPathMismatch).as("claim approval path mismatches").isEmpty();
        assertThat(reimbMismatch).as("reimbursement status mismatches").isEmpty();
    }
}
