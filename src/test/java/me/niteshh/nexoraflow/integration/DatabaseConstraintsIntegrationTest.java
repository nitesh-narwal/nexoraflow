package me.niteshh.nexoraflow.integration;

import static me.niteshh.nexoraflow.support.ExpenseTestSupport.claim;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.line;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.receipt;
import static me.niteshh.nexoraflow.support.TestWorld.ALICE;
import static me.niteshh.nexoraflow.support.TestWorld.CC_MAIN;
import static me.niteshh.nexoraflow.support.TestWorld.LEAD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.expense.ExpenseSubmissionResult;
import me.niteshh.nexoraflow.expense.ExpenseWorkflowService;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

/** The database itself enforces the rules the application relies on (defence in depth). */
class DatabaseConstraintsIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ExpenseWorkflowService expenses;
    @Autowired ApprovalService approvals;

    private void violates(String sql, String messagePart) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(messagePart);
    }

    private String pendingClaim() {
        ExpenseSubmissionResult r = expenses.submit(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("c-" + System.nanoTime()))));
        expenses.evaluate(r.claim().getExpenseClaimId(), ALICE);
        return r.claim().getExpenseClaimId();
    }

    @Test
    void employeeEmailUniquenessIsEnforced() {
        String existing = jdbc.queryForObject("SELECT email FROM employee WHERE employee_id = 'EMP-000008'", String.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE employee SET email = ? WHERE employee_id = 'EMP-000009'", existing.toUpperCase()))
                .hasStackTraceContaining("ux_employee_email_lower");
    }

    @Test
    void anEmployeeCannotBeTheirOwnManagerAndTheHierarchyCannotContainCycles() {
        violates("UPDATE employee SET manager_id = employee_id WHERE employee_id = 'EMP-000008'", "cannot be their own manager");
        // EMP-000007 manages EMP-000008; making EMP-000008 the manager of EMP-000007 would create a cycle
        violates("UPDATE employee SET manager_id = 'EMP-000008' WHERE employee_id = 'EMP-000007'", "cycle detected");
        // a longer cycle through the CEO
        violates("UPDATE employee SET manager_id = 'EMP-000008' WHERE employee_id = 'EMP-000001'", "cycle detected");
    }

    @Test
    void employmentDatesMustBeConsistent() {
        violates("UPDATE employee SET termination_date = '2019-01-01', employment_status = 'TERMINATED' WHERE employee_id = 'EMP-000008'", "ck_employee_dates");
        violates("UPDATE employee SET employment_status = 'TERMINATED' WHERE employee_id = 'EMP-000009'", "ck_employee_termination_consistency");
        violates("UPDATE employee SET employment_status = 'RETIRED' WHERE employee_id = 'EMP-000009'", "employee_employment_status_check");
    }

    @Test
    void foreignKeysAreEnforced() {
        violates("INSERT INTO leave_request (leave_request_id, employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, request_status, created_at, updated_at) "
                + "VALUES ('LVE-2026-009999', 'EMP-424242', 'LPOL-ANNUAL-US', '2026-11-02', '2026-11-03', 2, '2026-10-22', now(), 'SUBMITTED', now(), now())", "violates foreign key constraint");
    }

    @Test
    void invertedLeaveRangesAreOnlyAllowedOnRejectedRecords() {
        String insert = "INSERT INTO leave_request (leave_request_id, employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, request_status, created_at, updated_at) "
                + "VALUES ('LVE-2026-009998', 'EMP-000008', 'LPOL-ANNUAL-US', '2026-11-10', '2026-11-05', 0, '2026-10-22', now(), '%s', now(), now())";
        violates(String.format(insert, "SUBMITTED"), "ck_leave_date_range");
        assertThat(jdbc.update(String.format(insert, "REJECTED"))).isEqualTo(1);
    }

    @Test
    void availableBalanceIsAGeneratedColumnThatCannotBeWritten() {
        violates("UPDATE leave_balance SET available_balance = 999 WHERE employee_id = 'EMP-000008'", "can only be updated to DEFAULT");
        jdbc.update("UPDATE leave_balance SET used_balance = used_balance + 3 WHERE employee_id = 'EMP-000008' AND leave_policy_id = 'LPOL-ANNUAL-US'");
        assertThat(jdbc.queryForObject("SELECT available_balance FROM leave_balance WHERE employee_id = 'EMP-000008' AND leave_policy_id = 'LPOL-ANNUAL-US'", java.math.BigDecimal.class))
                .isEqualByComparingTo("19.00"); // 20 + 2 carried - 3
    }

    @Test
    void usedAndPendingBalancesCanNeverBecomeNegative() {
        violates("UPDATE leave_balance SET used_balance = -1 WHERE employee_id = 'EMP-000008'", "leave_balance_used_balance_check");
    }

    @Test
    void auditEventsAreAppendOnly() {
        pendingClaim();
        violates("UPDATE audit_event SET reason = 'tampered'", "append-only");
        violates("DELETE FROM audit_event", "append-only");
        violates("UPDATE decision_trace SET decision = 'APPROVED'", "append-only");
    }

    @Test
    void completedApprovalStepsCannotBeOverwrittenOrDeleted() {
        String claimId = pendingClaim();
        String stepId = approvals.stepsOf(RequestType.EXPENSE, claimId).get(0).getApprovalStepId();
        assertThat(approvals.approve(stepId, new ApprovalService.ActionCommand(LEAD, "ok", "ok")).accepted()).isTrue();
        violates("UPDATE approval_step SET approval_status = 'REJECTED' WHERE approval_step_id = '" + stepId + "'", "already APPROVED and cannot be modified");
        violates("UPDATE approval_step SET approver_employee_id = 'EMP-000001' WHERE approval_step_id = '" + stepId + "'", "cannot be modified");
        violates("DELETE FROM approval_step WHERE approval_step_id = '" + stepId + "'", "append-only");
    }

    @Test
    void theDatabaseRefusesAnApprovalStepAssignedToTheRequester() {
        String claimId = pendingClaim();
        violates("INSERT INTO approval_step (approval_step_id, request_type, expense_claim_id, step_sequence, approval_level, approver_role, approver_employee_id, approval_status, created_at, updated_at) "
                + "VALUES ('APR-" + claimId + "-09', 'EXPENSE', '" + claimId + "', 9, 3, 'MANAGER', 'EMP-000008', 'WAITING', now(), now())", "Self approval is not allowed");
    }

    @Test
    void reimbursementsRequireAnApprovedEligibleClaimAndAreUniquePerClaim() {
        String claimId = pendingClaim();
        // the claim is only PENDING_APPROVAL: the trigger refuses a reimbursement
        violates("INSERT INTO reimbursement (reimbursement_id, expense_claim_id, employee_id, approved_amount, currency, reimbursement_status, approved_at, created_at, updated_at) "
                + "VALUES ('RMB-2026-009999', '" + claimId + "', 'EMP-000008', 40, 'USD', 'PENDING_PAYMENT', now(), now(), now())", "Reimbursement refused");
        String stepId = approvals.stepsOf(RequestType.EXPENSE, claimId).get(0).getApprovalStepId();
        approvals.approve(stepId, new ApprovalService.ActionCommand(LEAD, "ok", "ok")); // creates the one reimbursement
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reimbursement WHERE expense_claim_id = ?", Integer.class, claimId)).isEqualTo(1);
        violates("INSERT INTO reimbursement (reimbursement_id, expense_claim_id, employee_id, approved_amount, currency, reimbursement_status, approved_at, created_at, updated_at) "
                + "VALUES ('RMB-2026-009998', '" + claimId + "', 'EMP-000008', 40, 'USD', 'PENDING_PAYMENT', now(), now(), now())", "ux_reimbursement_claim");
    }

    @Test
    void balanceLedgerAllowsOnlyOneConsumptionPerRequest() {
        String balance = jdbc.queryForObject("SELECT leave_balance_id FROM leave_balance WHERE employee_id = 'EMP-000008' AND leave_policy_id = 'LPOL-ANNUAL-US'", String.class);
        jdbc.update("INSERT INTO leave_request (leave_request_id, employee_id, leave_policy_id, start_date, end_date, requested_days, submission_date, submitted_at, request_status, created_at, updated_at) "
                + "VALUES ('LVE-2026-009001', 'EMP-000008', 'LPOL-ANNUAL-US', '2026-11-02', '2026-11-03', 2, '2026-10-22', now(), 'APPROVED', now(), now())");
        String txn = "INSERT INTO leave_balance_transaction (txn_id, leave_balance_id, leave_request_id, txn_type, used_delta, pending_delta, txn_date, occurred_at, actor, reason) "
                + "VALUES ('%s', '" + balance + "', 'LVE-2026-009001', 'CONSUME', 2, -2, '2026-10-22', now(), 'SYSTEM', 'x')";
        assertThat(jdbc.update(String.format(txn, "LBT-9000001"))).isEqualTo(1);
        violates(String.format(txn, "LBT-9000002"), "ux_lbt_request_once");
        violates("UPDATE leave_balance_transaction SET used_delta = 5", "append-only");
    }

    @Test
    void effectiveDatedRelationshipsCannotOverlap() {
        // an employee has one PRIMARY cost center at a time
        violates("INSERT INTO employee_cost_center (assignment_id, employee_id, cost_center_id, assignment_type, effective_from, created_at) "
                + "VALUES ('ECC-900001', 'EMP-000008', 'CC-002', 'PRIMARY', '2026-01-01', now())", "ex_ecc_primary_no_overlap");
        // two ACTIVE versions of one expense policy cannot overlap
        violates("INSERT INTO expense_policy (expense_policy_id, policy_name, expense_category, country, employee_type, currency, maximum_per_transaction, maximum_daily_amount, "
                + "maximum_monthly_amount, receipt_required, effective_from, policy_status, created_at, updated_at) VALUES ('XPOL-DUP', 'dup', 'MEALS', 'US', 'ALL', 'USD', 10, 20, 30, true, '2026-06-01', 'ACTIVE', now(), now())",
                "ex_expense_policy_no_overlap");
        // budgets of one cost center cannot overlap
        violates("INSERT INTO budget (budget_id, cost_center_id, fiscal_year, period_start, period_end, allocated_amount, currency, status, created_at, updated_at) "
                + "VALUES ('BUD-DUP', 'CC-001', 2026, '2026-03-15', '2026-04-15', 100, 'USD', 'ACTIVE', now(), now())", "ex_budget_no_overlap");
    }

    @Test
    void claimsInTheWorkflowMustHaveACostCenterAndReceiptsMustBeHashed() {
        violates("INSERT INTO expense_claim (expense_claim_id, employee_id, claim_date, expense_period_start, expense_period_end, submission_date, submitted_at, currency, total_amount, claim_status, eligibility_status, created_at, updated_at) "
                + "VALUES ('EXP-2026-009001', 'EMP-000008', '2026-10-22', '2026-10-20', '2026-10-20', '2026-10-22', now(), 'USD', 10, 'PENDING_APPROVAL', 'ELIGIBLE', now(), now())", "ck_claim_cost_center_when_live");
        violates("INSERT INTO receipt (receipt_id, employee_id, file_name, file_type, file_size_bytes, uploaded_at, receipt_hash, receipt_status) VALUES ('RCP-900001', 'EMP-000008', 'a.pdf', 'PDF', 10, now(), 'not-a-hash', 'VALID')",
                "receipt_receipt_hash_check");
    }

    @Test
    void foreignKeyBetweenLineAndClaimAndNonPositiveAmountsAreGuarded() {
        violates("INSERT INTO expense_line (expense_line_id, expense_claim_id, line_number, expense_category, merchant_name, transaction_date, amount, currency, line_status) "
                + "VALUES ('EXPL-X-01', 'EXP-NOPE', 1, 'MEALS', 'x', '2026-10-01', 10, 'USD', 'SUBMITTED')", "violates foreign key constraint");
        String claimId = pendingClaim();
        violates("INSERT INTO expense_line (expense_line_id, expense_claim_id, line_number, expense_category, merchant_name, transaction_date, amount, currency, line_status) "
                + "VALUES ('EXPL-" + claimId + "-05', '" + claimId + "', 5, 'MEALS', 'x', '2026-10-01', -5, 'USD', 'VALID')", "ck_line_amount");
    }
}
