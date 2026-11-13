package me.niteshh.nexoraflow.integration;

import static me.niteshh.nexoraflow.support.ExpenseTestSupport.claim;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.claimIn;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.line;
import static me.niteshh.nexoraflow.support.ExpenseTestSupport.receipt;
import static me.niteshh.nexoraflow.support.TestWorld.ALICE;
import static me.niteshh.nexoraflow.support.TestWorld.BOB;
import static me.niteshh.nexoraflow.support.TestWorld.CC_CLOSED;
import static me.niteshh.nexoraflow.support.TestWorld.CC_MAIN;
import static me.niteshh.nexoraflow.support.TestWorld.CC_OTHER;
import static me.niteshh.nexoraflow.support.TestWorld.CC_TIGHT;
import static me.niteshh.nexoraflow.support.TestWorld.CEO;
import static me.niteshh.nexoraflow.support.TestWorld.ENG_MGR;
import static me.niteshh.nexoraflow.support.TestWorld.INDIA;
import static me.niteshh.nexoraflow.support.TestWorld.FIN_MGR;
import static me.niteshh.nexoraflow.support.TestWorld.LEAD;
import static me.niteshh.nexoraflow.support.TestWorld.ORPHAN;
import static me.niteshh.nexoraflow.support.TestWorld.TERMINATED;
import static me.niteshh.nexoraflow.support.TestWorld.VP_ENG;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApprovalStep;
import me.niteshh.nexoraflow.approval.ApproverRole;
import me.niteshh.nexoraflow.audit.AuditEvent;
import me.niteshh.nexoraflow.audit.AuditEventRepository;
import me.niteshh.nexoraflow.audit.DetectedAnomalyRepository;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.ReviewAction;
import me.niteshh.nexoraflow.common.ReviewCommand;
import me.niteshh.nexoraflow.expense.ExpenseClaim;
import me.niteshh.nexoraflow.expense.ExpenseClaimRepository;
import me.niteshh.nexoraflow.expense.ExpenseClaimStatus;
import me.niteshh.nexoraflow.expense.ExpenseSubmission;
import me.niteshh.nexoraflow.expense.ExpenseSubmissionResult;
import me.niteshh.nexoraflow.expense.ExpenseWorkflowService;
import me.niteshh.nexoraflow.expense.LineStatus;
import me.niteshh.nexoraflow.intake.IntakeStatus;
import me.niteshh.nexoraflow.intake.SubmissionRecordRepository;
import me.niteshh.nexoraflow.reimbursement.AttemptOutcome;
import me.niteshh.nexoraflow.reimbursement.ReimbursementAttempt;
import me.niteshh.nexoraflow.reimbursement.ReimbursementRepository;
import me.niteshh.nexoraflow.reimbursement.ReimbursementResult;
import me.niteshh.nexoraflow.reimbursement.ReimbursementService;
import me.niteshh.nexoraflow.reimbursement.ReimbursementStatus;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

class ExpenseWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired ExpenseWorkflowService expenses;
    @Autowired ApprovalService approvals;
    @Autowired ExpenseClaimRepository claims;
    @Autowired ReimbursementService reimbursementService;
    @Autowired ReimbursementRepository reimbursements;
    @Autowired AuditEventRepository audit;
    @Autowired DetectedAnomalyRepository anomalies;
    @Autowired SubmissionRecordRepository submissions;
    @Autowired TransactionTemplate tx;
    @Autowired me.niteshh.nexoraflow.validation.ValidationService validation;

    private ExpenseClaim submitAndEvaluate(ExpenseSubmission submission, String actor) {
        ExpenseSubmissionResult r = expenses.submit(submission);
        assertThat(r.accepted()).as("intake errors: %s", r.errors()).isTrue();
        expenses.evaluate(r.claim().getExpenseClaimId(), actor);
        return load(r.claim().getExpenseClaimId());
    }

    private ExpenseClaim load(String id) {
        return tx.execute(s -> {
            ExpenseClaim c = claims.findById(id).orElseThrow();
            c.getLines().size();
            return c;
        });
    }

    private List<ApprovalStep> steps(String claimId) {
        return approvals.stepsOf(RequestType.EXPENSE, claimId);
    }

    private ApprovalService.ActionCommand by(String actor) {
        return new ApprovalService.ActionCommand(actor, "ok", "approved");
    }

    @Test
    void validClaimCompletesApprovalAndGeneratesExactlyOneReimbursement() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("r-1"))), ALICE);
        assertThat(c.getExpenseClaimId()).isEqualTo("EXP-2026-000001");
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        assertThat(c.getTotalAmount()).isEqualByComparingTo("40.00");
        assertThat(c.getLines().get(0).getLineStatus()).isEqualTo(LineStatus.VALID);
        assertThat(c.getLines().get(0).getExpenseLineId()).isEqualTo("EXPL-EXP-2026-000001-01");

        List<ApprovalStep> s = steps(c.getExpenseClaimId());
        assertThat(s).hasSize(1);
        assertThat(s.get(0).getApprovalStepId()).isEqualTo("APR-EXP-2026-000001-01");
        assertThat(s.get(0).getApproverRole()).isEqualTo(ApproverRole.DIRECT_MANAGER);
        assertThat(s.get(0).getApproverEmployeeId()).isEqualTo(LEAD);
        // no reimbursement before final approval
        assertThat(reimbursements.existsByExpenseClaimId(c.getExpenseClaimId())).isFalse();

        assertThat(approvals.approve(s.get(0).getApprovalStepId(), by(LEAD)).accepted()).isTrue();
        assertThat(load(c.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.APPROVED);
        var reimbursement = reimbursements.findByExpenseClaimId(c.getExpenseClaimId()).orElseThrow();
        assertThat(reimbursement.getReimbursementId()).isEqualTo("RMB-2026-000001");
        assertThat(reimbursement.getApprovedAmount()).isEqualByComparingTo("40.00");
        assertThat(reimbursement.getReimbursementStatus()).isEqualTo(ReimbursementStatus.PENDING_PAYMENT);

        // a second attempt to reimburse the same claim is rejected and recorded; still exactly one reimbursement
        ReimbursementResult duplicate = reimbursementService.requestReimbursement(c.getExpenseClaimId(), "PAYROLL");
        assertThat(duplicate.outcome()).isEqualTo(AttemptOutcome.DUPLICATE_REJECTED);
        assertThat(duplicate.reasonCode()).isEqualTo(ReasonCode.DUPLICATE_REIMBURSEMENT);
        assertThat(reimbursements.count()).isEqualTo(1);
        List<ReimbursementAttempt> attempts = reimbursementService.attemptsFor(c.getExpenseClaimId());
        assertThat(attempts).extracting(ReimbursementAttempt::getOutcome)
                .containsExactly(AttemptOutcome.CREATED, AttemptOutcome.DUPLICATE_REJECTED);
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(EntityType.REIMBURSEMENT, reimbursement.getReimbursementId()))
                .extracting(AuditEvent::getEventType).contains("REIMBURSEMENT_CREATED", "REIMBURSEMENT_DUPLICATE_REJECTED");
    }

    @Test
    void multiLevelApprovalRunsInOrderAndRejectsOutOfOrderActions() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("TRAVEL", "Skyway Air", date(2026, 10, 19), "1800.00", receipt("t-1")),
                line("TRAVEL", "Skyway Air", date(2026, 10, 20), "1800.00", receipt("t-2"))), ALICE);
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        List<ApprovalStep> s = steps(c.getExpenseClaimId());
        assertThat(s).extracting(ApprovalStep::getApprovalLevel).containsExactly(1, 2);
        assertThat(s).extracting(ApprovalStep::getApproverEmployeeId).containsExactly(ENG_MGR, FIN_MGR);
        assertThat(s).extracting(ApprovalStep::getApprovalStatus).containsExactly(ApprovalStatus.PENDING, ApprovalStatus.WAITING);

        // finance cannot act before the manager: rejected and recorded, state unchanged
        var early = approvals.approve(s.get(1).getApprovalStepId(), by(FIN_MGR));
        assertThat(early.accepted()).isFalse();
        assertThat(early.reasonCode()).isEqualTo(ReasonCode.INVALID_STEP_ORDER);
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(EntityType.EXPENSE_CLAIM, c.getExpenseClaimId()))
                .extracting(AuditEvent::getEventType).contains("INVALID_TRANSITION_REJECTED");

        assertThat(approvals.approve(s.get(0).getApprovalStepId(), by(ENG_MGR)).accepted()).isTrue();
        assertThat(load(c.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        assertThat(steps(c.getExpenseClaimId()).get(1).getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(reimbursements.existsByExpenseClaimId(c.getExpenseClaimId())).isFalse(); // not yet: level 2 open

        assertThat(approvals.approve(s.get(1).getApprovalStepId(), by(FIN_MGR)).accepted()).isTrue();
        assertThat(load(c.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.APPROVED);
        assertThat(reimbursements.existsByExpenseClaimId(c.getExpenseClaimId())).isTrue();
    }

    @Test
    void rejectionStopsTheWorkflowAndNeverCreatesAReimbursement() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("TRAVEL", "Skyway Air", date(2026, 10, 19), "1800.00", receipt("t-1")),
                line("TRAVEL", "Skyway Air", date(2026, 10, 20), "1800.00", receipt("t-2"))), ALICE);
        List<ApprovalStep> s = steps(c.getExpenseClaimId());
        assertThat(approvals.reject(s.get(0).getApprovalStepId(), new ApprovalService.ActionCommand(ENG_MGR, "too expensive", "budget")).accepted()).isTrue();
        assertThat(load(c.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        List<ApprovalStep> after = steps(c.getExpenseClaimId());
        assertThat(after.get(0).getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(after.get(1).getApprovalStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        // the finance step can no longer be used, and a completed decision cannot be overwritten
        assertThat(approvals.approve(after.get(1).getApprovalStepId(), by(FIN_MGR)).reasonCode()).isEqualTo(ReasonCode.STEP_ALREADY_COMPLETED);
        assertThat(approvals.approve(after.get(0).getApprovalStepId(), by(ENG_MGR)).reasonCode()).isEqualTo(ReasonCode.STEP_ALREADY_COMPLETED);
        assertThat(reimbursements.count()).isZero();
        assertThat(reimbursementService.requestReimbursement(c.getExpenseClaimId(), "PAYROLL").outcome())
                .isEqualTo(AttemptOutcome.NOT_ELIGIBLE_REJECTED);
        assertThat(reimbursements.count()).isZero();
    }

    @Test
    void requesterCannotApproveOwnClaimAndTheAttemptIsAudited() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("r-1"))), ALICE);
        ApprovalStep step = steps(c.getExpenseClaimId()).get(0);

        var self = approvals.approve(step.getApprovalStepId(), by(ALICE));
        assertThat(self.accepted()).isFalse();
        assertThat(self.reasonCode()).isEqualTo(ReasonCode.SELF_APPROVAL_NOT_ALLOWED);
        var stranger = approvals.approve(step.getApprovalStepId(), by(CEO));
        assertThat(stranger.reasonCode()).isEqualTo(ReasonCode.UNAUTHORIZED_APPROVER);

        assertThat(steps(c.getExpenseClaimId()).get(0).getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(load(c.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        List<AuditEvent> attempts = audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(EntityType.EXPENSE_CLAIM, c.getExpenseClaimId())
                .stream().filter(e -> e.getEventType().equals("APPROVAL_ATTEMPT_REJECTED")).toList();
        assertThat(attempts).extracting(AuditEvent::getActor).containsExactly(ALICE, CEO);
        assertThat(attempts).extracting(AuditEvent::getReason).containsExactly("SELF_APPROVAL_NOT_ALLOWED", "UNAUTHORIZED_APPROVER");
    }

    @Test
    void missingReceiptIsFlagged() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", null)), ALICE);
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(c.getDecisionReason()).contains("RECEIPT_MISSING");
        assertThat(c.getLines().get(0).getLineStatus()).isEqualTo(LineStatus.FLAGGED);
        assertThat(steps(c.getExpenseClaimId())).isEmpty();
        // a small meal below the receipt threshold needs no receipt
        ExpenseClaim small = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Kiosk", date(2026, 10, 21), "12.00", null)), ALICE);
        assertThat(small.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
    }

    @Test
    void policyLimitsAreEnforcedPerTransactionPerDayAndPerMonth() {
        ExpenseClaim perTxn = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Steakhouse", date(2026, 10, 20), "90.00", receipt("p-1"))), ALICE);
        assertThat(perTxn.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(perTxn.getDecisionReason()).contains("POLICY_LIMIT_EXCEEDED");

        ExpenseClaim daily = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("MEALS", "Steakhouse", date(2026, 10, 19), "70.00", receipt("d-1")),
                line("MEALS", "Pasta Place", date(2026, 10, 19), "70.00", receipt("d-2"))), ALICE); // 140 > 120 per day
        assertThat(daily.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(daily.getDecisionReason()).contains("POLICY_LIMIT_EXCEEDED");

        // monthly: 12 approved-in-flight meals of 70 on distinct days = 840; the next 200 pushes it over 1000
        for (int day = 1; day <= 12; day++) {
            ExpenseClaim ok = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Deli " + day, date(2026, 10, day), "70.00", receipt("m-" + day))), ALICE);
            assertThat(ok.getClaimStatus()).as("day %d", day).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        }
        ExpenseClaim monthly = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Banquet", date(2026, 10, 14), "70.00", receipt("m-x")),
                line("MEALS", "Banquet", date(2026, 10, 15), "70.00", receipt("m-y")), line("MEALS", "Banquet", date(2026, 10, 16), "70.00", receipt("m-z"))), ALICE);
        assertThat(monthly.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED); // 840 + 210 = 1050 > 1000
    }

    @Test
    void suspiciousSplitClaimsAreDetectedAndUnrelatedClaimsAreNot() {
        ExpenseClaim first = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("CLIENT_ENTERTAINMENT", "Bistro Nova", date(2026, 10, 19), "450.00", receipt("s-1"))), ALICE);
        assertThat(first.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);

        // different merchant within the same window: unrelated, must NOT be flagged
        ExpenseClaim unrelated = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("CLIENT_ENTERTAINMENT", "Harbour Grill", date(2026, 10, 18), "400.00", receipt("s-u"))), ALICE);
        assertThat(unrelated.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);

        // same merchant a day later: 450 + 400 = 850 > 500 boundary while each is below it
        ExpenseClaim second = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("CLIENT_ENTERTAINMENT", "Bistro Nova", date(2026, 10, 20), "400.00", receipt("s-2"))), ALICE);
        assertThat(second.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(second.getDecisionReason()).contains("SUSPICIOUS_SPLIT_CLAIM");
        // the earlier, still-pending related claim is held too and its approval steps cancelled
        ExpenseClaim firstAfter = load(first.getExpenseClaimId());
        assertThat(firstAfter.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(steps(first.getExpenseClaimId())).allMatch(st -> st.getApprovalStatus() == ApprovalStatus.CANCELLED);
        assertThat(load(unrelated.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        assertThat(anomalies.findByEntityTypeAndEntityIdOrderByAnomalyId(EntityType.EXPENSE_CLAIM, second.getExpenseClaimId()))
                .anyMatch(a -> a.getAnomalyType().equals("SUSPICIOUS_SPLIT_CLAIM") && a.getRelatedEntityIds().contains(first.getExpenseClaimId()));

        // a claim far outside the 3-day window is unrelated too
        ExpenseClaim later = submitAndEvaluate(claim(ALICE, CC_MAIN,
                line("CLIENT_ENTERTAINMENT", "Bistro Nova", date(2026, 10, 22), "300.00", receipt("s-3"))), ALICE);
        assertThat(later.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED); // within 3 days of the 20th: still linked (chain)
    }

    @Test
    void missingOrInvalidCostCenterIsHeldAndTheOriginalValueIsPreserved() {
        ExpenseSubmissionResult unknown = expenses.submit(claim(ALICE, "CC-999",
                line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("c-1"))));
        assertThat(unknown.intakeStatus()).isEqualTo(IntakeStatus.ACCEPTED_WITH_ERRORS);
        ExpenseClaim stored = load(unknown.claim().getExpenseClaimId());
        assertThat(stored.getCostCenterId()).isNull();                         // no invalid FK in the canonical table
        assertThat(stored.getSubmittedCostCenterRef()).isEqualTo("CC-999");    // original value preserved
        assertThat(submissions.findByExpenseClaimId(stored.getExpenseClaimId()).orElseThrow().getRawPayload()).contains("CC-999");
        expenses.evaluate(stored.getExpenseClaimId(), ALICE);
        ExpenseClaim held = load(stored.getExpenseClaimId());
        assertThat(held.getClaimStatus()).isEqualTo(ExpenseClaimStatus.ON_HOLD);
        assertThat(held.getDecisionReason()).contains("INVALID_COST_CENTER");

        ExpenseClaim blank = submitAndEvaluate(claim(ALICE, "", line("MEALS", "Cafe Roma", date(2026, 10, 21), "40.00", receipt("c-2"))), ALICE);
        assertThat(blank.getClaimStatus()).isEqualTo(ExpenseClaimStatus.ON_HOLD);

        ExpenseClaim closed = submitAndEvaluate(claim(ALICE, CC_CLOSED, line("MEALS", "Cafe Roma", date(2026, 10, 18), "40.00", receipt("c-3"))), ALICE);
        assertThat(closed.getClaimStatus()).isEqualTo(ExpenseClaimStatus.ON_HOLD);
        assertThat(closed.getCostCenterId()).isEqualTo(CC_CLOSED); // valid FK, but the center is closed

        // an analyst can fix the cost center and release the held claim into approval
        assertThat(expenses.correctCostCenter(held.getExpenseClaimId(), ALICE, CC_MAIN).accepted()).isTrue();
        assertThat(expenses.review(held.getExpenseClaimId(), new ReviewCommand(VP_ENG, ReviewAction.RELEASE, "fixed")).accepted()).isTrue();
        ExpenseClaim released = load(held.getExpenseClaimId());
        assertThat(released.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        assertThat(released.getSubmittedCostCenterRef()).isEqualTo("CC-999");
        assertThat(steps(released.getExpenseClaimId())).hasSize(1);
    }

    @Test
    void expensesOutsideTheEmploymentPeriodAreRejectedButPreserved() {
        ExpenseClaim beforeJoining = submitAndEvaluate(claim(BOB, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 5, 20), "40.00", receipt("e-1"))), BOB);
        assertThat(beforeJoining.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(beforeJoining.getDecisionReason()).contains("OUTSIDE_EMPLOYMENT_PERIOD");
        assertThat(beforeJoining.getLines()).hasSize(1);                                    // transaction preserved
        assertThat(beforeJoining.getLines().get(0).getLineStatus()).isEqualTo(LineStatus.INVALID);

        ExpenseClaim afterTermination = submitAndEvaluate(claim(TERMINATED, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 7, 1), "40.00", receipt("e-2"))), TERMINATED);
        assertThat(afterTermination.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(afterTermination.getDecisionReason()).contains("OUTSIDE_EMPLOYMENT_PERIOD");

        // an ex-employee can still claim an expense incurred BEFORE termination
        ExpenseClaim beforeTermination = submitAndEvaluate(claim(TERMINATED, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 5, 20), "40.00", receipt("e-3"))), TERMINATED);
        assertThat(beforeTermination.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
    }

    @Test
    void budgetIsDerivedFromSourceClaimsAndExhaustionPutsTheClaimOnHold() {
        // cost center CC-004 has a 3,000 USD budget for October
        ExpenseClaim a = submitAndEvaluate(claim(ALICE, CC_TIGHT, line("TRAVEL", "Skyway Air", date(2026, 10, 15), "2000.00", receipt("b-1"))), ALICE);
        assertThat(a.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        ExpenseClaim b = submitAndEvaluate(claim(BOB, CC_TIGHT, line("TRAVEL", "Rail Europe", date(2026, 10, 16), "1500.00", receipt("b-2"))), BOB);
        assertThat(b.getClaimStatus()).isEqualTo(ExpenseClaimStatus.ON_HOLD);
        assertThat(b.getDecisionReason()).contains("INSUFFICIENT_BUDGET");
        // once the first claim is rejected its commitment is released: the budget is recomputed from source claims
        assertThat(approvals.reject(steps(a.getExpenseClaimId()).get(0).getApprovalStepId(), new ApprovalService.ActionCommand(ENG_MGR, "no", "no")).accepted()).isTrue();
        // Bob withdraws the held claim and resubmits it
        assertThat(expenses.cancel(b.getExpenseClaimId(), BOB, "resubmitting").accepted()).isTrue();
        ExpenseClaim c = submitAndEvaluate(claim(BOB, CC_TIGHT, line("TRAVEL", "Rail Europe", date(2026, 10, 17), "1500.00", receipt("b-3"))), BOB);
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
    }

    @Test
    void unauthorizedCostCenterUseIsFlagged() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_OTHER, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("u-1"))), ALICE);
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(c.getDecisionReason()).contains("UNAUTHORIZED_COST_CENTER");
    }

    @Test
    void duplicateReceiptAndDuplicateSubmissionAreFlagged() {
        ExpenseClaim original = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Cafe A", date(2026, 10, 20), "40.00", receipt("same-file"))), ALICE);
        assertThat(original.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);

        ExpenseClaim reused = submitAndEvaluate(claim(BOB, CC_MAIN, line("MEALS", "Cafe B", date(2026, 10, 21), "45.00", receipt("same-file"))), BOB);
        assertThat(reused.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(reused.getDecisionReason()).contains("DUPLICATE_RECEIPT");
        assertThat(anomalies.findByEntityTypeAndEntityIdOrderByAnomalyId(EntityType.EXPENSE_CLAIM, reused.getExpenseClaimId()))
                .anyMatch(a -> a.getAnomalyType().equals("DUPLICATE_RECEIPT") && a.getRelatedEntityIds().contains(original.getExpenseClaimId()));

        ExpenseClaim dup = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Cafe A", date(2026, 10, 20), "40.00", receipt("other-file"))), ALICE);
        assertThat(dup.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(dup.getDecisionReason()).contains("DUPLICATE_SUBMISSION");
    }

    @Test
    void approverEscalatesWhenTheManagerIsInactiveAndFailsWhenNoApproverExists() {
        ExpenseClaim c = submitAndEvaluate(claim(ORPHAN, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("o-1"))), ORPHAN);
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        assertThat(steps(c.getExpenseClaimId()).get(0).getApproverEmployeeId()).isEqualTo(VP_ENG); // inactive manager skipped
        assertThat(audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(EntityType.EXPENSE_CLAIM, c.getExpenseClaimId()))
                .extracting(AuditEvent::getEventType).contains("APPROVER_ESCALATED_UNAVAILABLE");

        ExpenseClaim ceo = submitAndEvaluate(claim(CEO, CC_MAIN, line("TRAVEL", "Skyway Air", date(2026, 10, 20), "300.00", receipt("o-2"))), CEO);
        assertThat(ceo.getClaimStatus()).isEqualTo(ExpenseClaimStatus.NEEDS_REVIEW);
        assertThat(ceo.getDecisionReason()).contains("NO_VALID_APPROVER");
    }

    @Test
    void inactiveApprovalRulesAreIgnoredWhenBuildingTheRoute() {
        ExpenseClaim c = submitAndEvaluate(claim(ALICE, CC_MAIN, line("TRAVEL", "Skyway Air", date(2026, 10, 20), "1200.00", receipt("i-1"))), ALICE);
        List<ApprovalStep> s = steps(c.getExpenseClaimId());
        assertThat(s).hasSize(1);                                  // the decommissioned FINANCE_HEAD rule is not applied
        assertThat(s.get(0).getApproverRole()).isEqualTo(ApproverRole.MANAGER);
        assertThat(s.get(0).getApproverEmployeeId()).isEqualTo(ENG_MGR);
    }

    @Test
    void invalidCurrencyAmountDateAndPolicyEffectiveDatesAreRejected() {
        ExpenseClaim ccy = submitAndEvaluate(claimIn("GBP", ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", receipt("v-1"))), ALICE);
        assertThat(ccy.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(ccy.getDecisionReason()).contains("INVALID_CURRENCY");

        ExpenseClaim negative = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "-15.00", null)), ALICE);
        assertThat(negative.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(negative.getDecisionReason()).contains("INVALID_AMOUNT");
        assertThat(negative.getTotalAmount()).isEqualByComparingTo("0.00");

        ExpenseClaim old = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2025, 12, 15), "40.00", receipt("v-2"))), ALICE);
        assertThat(old.getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(old.getDecisionReason()).contains("POLICY_NOT_EFFECTIVE"); // policy exists but starts 2026-01-01

        ExpenseSubmission inverted = new ExpenseSubmission(ALICE, date(2026, 10, 20), date(2026, 10, 10), CC_MAIN, "USD",
                List.of(line("MEALS", "Cafe Roma", date(2026, 10, 15), "40.00", receipt("v-3"))));
        ExpenseSubmissionResult r = expenses.submit(inverted);
        assertThat(load(r.claim().getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.REJECTED);
        assertThat(load(r.claim().getExpenseClaimId()).getDecisionReason()).contains("INVALID_DATE_RANGE");
    }

    @Test
    void invalidSubmissionsAreRejectedAtIntakeButStayInStaging() {
        ExpenseSubmissionResult unknownEmployee = expenses.submit(claim("EMP-424242", CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", null)));
        assertThat(unknownEmployee.accepted()).isFalse();
        assertThat(unknownEmployee.claim()).isNull();
        assertThat(submissions.findById(unknownEmployee.submissionId()).orElseThrow().getRawPayload()).contains("EMP-424242");

        ExpenseSubmissionResult category = expenses.submit(claim(ALICE, CC_MAIN, line("GAMBLING", "Casino", date(2026, 10, 20), "40.00", null)));
        assertThat(category.accepted()).isFalse();
        assertThat(category.errors()).anyMatch(e -> e.startsWith("UNSUPPORTED_CATEGORY"));
        assertThat(claims.count()).isZero();
    }

    @Test
    void analystCanReleaseAFlaggedClaimIntoApprovalOrRejectIt() {
        ExpenseClaim flagged = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Cafe Roma", date(2026, 10, 20), "40.00", null)), ALICE);
        assertThat(flagged.getClaimStatus()).isEqualTo(ExpenseClaimStatus.FLAGGED);
        assertThat(expenses.review(flagged.getExpenseClaimId(), new ReviewCommand(VP_ENG, ReviewAction.RELEASE, "receipt seen offline")).accepted()).isTrue();
        assertThat(load(flagged.getExpenseClaimId()).getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        assertThat(steps(flagged.getExpenseClaimId())).hasSize(1);
        // a claim that is not in an exception state cannot be reviewed
        assertThat(expenses.review(flagged.getExpenseClaimId(), new ReviewCommand(VP_ENG, ReviewAction.REJECT, "late")).accepted()).isFalse();
        // hard failures cannot be waived
        ExpenseClaim limit = submitAndEvaluate(claim(ALICE, CC_MAIN, line("MEALS", "Steakhouse", date(2026, 10, 20), "90.00", receipt("l-1"))), ALICE);
        assertThat(expenses.review(limit.getExpenseClaimId(), new ReviewCommand(VP_ENG, ReviewAction.RELEASE, "pls")).accepted()).isFalse();
    }

    @Test
    void anAmountThatRoundsToTheBoundaryStaysInTheLowerApprovalTierAndPassesRouteValidation() {
        // 41,666.67 INR x 0.012 = 500.00004 USD, i.e. 500.00 USD in cents: not above the 500.00 boundary (exclusive lower bound)
        ExpenseSubmission.Line fare = new ExpenseSubmission.Line("TRAVEL", "Skyway Air", date(2026, 10, 20), "Flight to Pune",
                new java.math.BigDecimal("41666.67"), "INR", null, receipt("in-1"));
        ExpenseClaim c = submitAndEvaluate(claimIn("INR", INDIA, CC_MAIN, fare), INDIA);
        assertThat(c.getClaimStatus()).isEqualTo(ExpenseClaimStatus.PENDING_APPROVAL);
        List<ApprovalStep> s = steps(c.getExpenseClaimId());
        assertThat(s).hasSize(1);
        assertThat(s.get(0).getApproverRole()).isEqualTo(ApproverRole.DIRECT_MANAGER);

        // the independent validator derives the same route from the same cent-rounded amount
        var routeCheck = validation.run().checks().stream().filter(x -> x.id().equals("BI-05")).findFirst().orElseThrow();
        assertThat(routeCheck.findings()).isEmpty();
    }
}
