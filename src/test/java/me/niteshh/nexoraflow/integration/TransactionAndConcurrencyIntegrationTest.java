package me.niteshh.nexoraflow.integration;

import static me.niteshh.nexoraflow.support.ExpenseTestSupport.claim;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.line;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.receipt;
import static me.niteshh.nexoraflow.support.TestWorld.ALICE;
import static me.niteshh.nexoraflow.support.TestWorld.CC_MAIN;
import static me.niteshh.nexoraflow.support.TestWorld.LEAD;
import static me.niteshh.nexoraflow.support.TestWorld.LP_ANNUAL_US;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.expense.ExpenseSubmissionResult;
import me.niteshh.nexoraflow.expense.ExpenseWorkflowService;
import me.niteshh.nexoraflow.leave.LeaveSubmission;
import me.niteshh.nexoraflow.leave.LeaveWorkflowService;
import me.niteshh.nexoraflow.reimbursement.AttemptOutcome;
import me.niteshh.nexoraflow.reimbursement.ReimbursementResult;
import me.niteshh.nexoraflow.reimbursement.ReimbursementService;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;
import me.niteshh.nexoraflow.support.TestWorld;

class TransactionAndConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired LeaveWorkflowService leave;
    @Autowired ExpenseWorkflowService expenses;
    @Autowired ApprovalService approvals;
    @Autowired ReimbursementService reimbursements;

    @Test
    void aFailureInsideATransactionRollsBackEverythingIncludingBusinessIdAllocation() {
        LeaveSubmission submission = new LeaveSubmission(ALICE, LP_ANNUAL_US, date(2026, 11, 2), date(2026, 11, 6), null, "x", null);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            leave.submit(submission);
            leave.submit(submission);
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_record", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isZero();
        // no gaps: the rolled-back allocations are released, the next request is number 1 again
        assertThat(leave.submit(submission).request().getLeaveRequestId()).isEqualTo("LVE-2026-000001");
    }

    @Test
    void aFailingFinalApprovalLeavesNoPartialState() {
        ExpenseSubmissionResult r = expenses.submit(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("rb-1"))));
        String claimId = r.claim().getExpenseClaimId();
        expenses.evaluate(claimId, ALICE);
        String stepId = approvals.stepsOf(RequestType.EXPENSE, claimId).get(0).getApprovalStepId();
        int eventsBefore = jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            approvals.approve(stepId, new ApprovalService.ActionCommand(LEAD, "ok", "ok")); // would approve + reimburse
            throw new IllegalStateException("payment gateway down");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT claim_status FROM expense_claim WHERE expense_claim_id = ?", String.class, claimId)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("SELECT approval_status FROM approval_step WHERE approval_step_id = ?", String.class, stepId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reimbursement", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(eventsBefore);
    }

    @Test
    void concurrentReimbursementRequestsCreateExactlyOneReimbursementAndRecordTheRest() throws Exception {
        ExpenseSubmissionResult r = expenses.submit(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("conc-1"))));
        String claimId = r.claim().getExpenseClaimId();
        expenses.evaluate(claimId, ALICE);
        approvals.approve(approvals.stepsOf(RequestType.EXPENSE, claimId).get(0).getApprovalStepId(), new ApprovalService.ActionCommand(LEAD, "ok", "ok"));
        // simulate a payroll system that has not created the reimbursement yet
        jdbc.update("DELETE FROM reimbursement_attempt");
        jdbc.update("DELETE FROM reimbursement");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReimbursementResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<ReimbursementResult> task = () -> {
                start.await();
                AppClock.setSimulatedTime(TestWorld.NOW);
                try {
                    return reimbursements.requestReimbursement(claimId, "PAYROLL");
                } finally {
                    AppClock.clearSimulatedTime();
                }
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        List<AttemptOutcome> outcomes = new ArrayList<>();
        for (Future<ReimbursementResult> f : futures) {
            outcomes.add(f.get().outcome());
        }
        pool.shutdown();

        assertThat(outcomes.stream().filter(o -> o == AttemptOutcome.CREATED)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o == AttemptOutcome.DUPLICATE_REJECTED)).hasSize(threads - 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reimbursement WHERE expense_claim_id = ?", Integer.class, claimId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reimbursement_attempt WHERE outcome = 'DUPLICATE_REJECTED'", Integer.class)).isEqualTo(threads - 1);
    }

    @Test
    void concurrentApprovalsOfTheSameStepAreSerialisedAndOnlyOneWins() throws Exception {
        ExpenseSubmissionResult r = expenses.submit(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("conc-2"))));
        String claimId = r.claim().getExpenseClaimId();
        expenses.evaluate(claimId, ALICE);
        String stepId = approvals.stepsOf(RequestType.EXPENSE, claimId).get(0).getApprovalStepId();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                AppClock.setSimulatedTime(TestWorld.NOW);
                try {
                    return approvals.approve(stepId, new ApprovalService.ActionCommand(LEAD, "ok", "ok")).accepted();
                } catch (RuntimeException optimisticLockOrConstraint) {
                    return false; // losers are rejected by optimistic locking / the immutability trigger
                } finally {
                    AppClock.clearSimulatedTime();
                }
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<Boolean> f : futures) {
            winners += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reimbursement WHERE expense_claim_id = ?", Integer.class, claimId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT claim_status FROM expense_claim WHERE expense_claim_id = ?", String.class, claimId)).isEqualTo("APPROVED");
    }
}
