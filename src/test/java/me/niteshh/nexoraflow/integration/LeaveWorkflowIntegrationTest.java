package me.niteshh.nexoraflow.integration;

import static me.niteshh.nexoraflow.support.TestWorld.ALICE;
import static me.niteshh.nexoraflow.support.TestWorld.BOB;
import static me.niteshh.nexoraflow.support.TestWorld.CONTRACTOR;
import static me.niteshh.nexoraflow.support.TestWorld.LEAD;
import static me.niteshh.nexoraflow.support.TestWorld.LP_ANNUAL_US;
import static me.niteshh.nexoraflow.support.TestWorld.LP_SICK;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApprovalStep;
import me.niteshh.nexoraflow.audit.AuditEvent;
import me.niteshh.nexoraflow.audit.AuditEventRepository;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.leave.BalanceTransactionType;
import me.niteshh.nexoraflow.leave.LeaveBalance;
import me.niteshh.nexoraflow.leave.LeaveBalanceService;
import me.niteshh.nexoraflow.leave.LeaveBalanceTransaction;
import me.niteshh.nexoraflow.leave.LeaveRequest;
import me.niteshh.nexoraflow.leave.LeaveRequestRepository;
import me.niteshh.nexoraflow.leave.LeaveRequestStatus;
import me.niteshh.nexoraflow.leave.LeaveSubmission;
import me.niteshh.nexoraflow.leave.LeaveSubmissionResult;
import me.niteshh.nexoraflow.leave.LeaveWorkflowService;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

class LeaveWorkflowIntegrationTest extends AbstractIntegrationTest {

    @Autowired LeaveWorkflowService leave;
    @Autowired ApprovalService approvals;
    @Autowired LeaveRequestRepository requests;
    @Autowired LeaveBalanceService balances;
    @Autowired AuditEventRepository audit;

    private LeaveSubmission submission(String employee, String policy, int sm, int sd, int em, int ed) {
        return new LeaveSubmission(employee, policy, date(2026, sm, sd), date(2026, em, ed), null, "test", null);
    }

    private LeaveBalance balance(String employee, String policy) {
        return balances.find(employee, policy, 2026).orElseThrow();
    }

    @Test
    void validLeaveIsEvaluatedApprovedAndConsumesBalanceExactlyOnce() {
        // 2026-11-02 (Mon) .. 2026-11-06 (Fri): 5 business days, 11 days notice (policy needs 7)
        LeaveSubmissionResult submitted = leave.submit(submission(ALICE, LP_ANNUAL_US, 11, 2, 11, 6));
        assertThat(submitted.accepted()).isTrue();
        String id = submitted.request().getLeaveRequestId();
        assertThat(id).isEqualTo("LVE-2026-000001");
        assertThat(balance(ALICE, LP_ANNUAL_US).computeAvailable()).isEqualByComparingTo("22.00"); // 20 granted + 2 carried forward

        var evaluated = leave.evaluate(id, ALICE);
        assertThat(evaluated.accepted()).isTrue();
        LeaveRequest request = requests.findById(id).orElseThrow();
        assertThat(request.getRequestStatus()).isEqualTo(LeaveRequestStatus.PENDING_APPROVAL);
        assertThat(request.getCalculatedBusinessDays()).isEqualByComparingTo("5");
        // pending reservation reduces available balance, used is untouched
        LeaveBalance reserved = balance(ALICE, LP_ANNUAL_US);
        assertThat(reserved.getPendingBalance()).isEqualByComparingTo("5.00");
        assertThat(reserved.getUsedBalance()).isEqualByComparingTo("0.00");
        assertThat(reserved.getAvailableBalance()).isEqualByComparingTo("17.00"); // DB generated column

        List<ApprovalStep> steps = approvals.stepsOf(RequestType.LEAVE, id);
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getApproverEmployeeId()).isEqualTo(LEAD);
        assertThat(steps.get(0).getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);

        var approved = approvals.approve(steps.get(0).getApprovalStepId(), new ApprovalService.ActionCommand(LEAD, "enjoy", "approved"));
        assertThat(approved.accepted()).isTrue();

        assertThat(requests.findById(id).orElseThrow().getRequestStatus()).isEqualTo(LeaveRequestStatus.APPROVED);
        LeaveBalance consumed = balance(ALICE, LP_ANNUAL_US);
        assertThat(consumed.getPendingBalance()).isEqualByComparingTo("0.00");
        assertThat(consumed.getUsedBalance()).isEqualByComparingTo("5.00");
        assertThat(consumed.getAvailableBalance()).isEqualByComparingTo("17.00"); // unchanged: not deducted twice

        List<LeaveBalanceTransaction> ledger = balances.history(consumed.getLeaveBalanceId());
        assertThat(ledger).extracting(LeaveBalanceTransaction::getTxnType).containsExactly(
                BalanceTransactionType.OPENING_GRANT, BalanceTransactionType.CARRY_FORWARD,
                BalanceTransactionType.PENDING_RESERVE, BalanceTransactionType.CONSUME);

        List<AuditEvent> events = audit.findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(EntityType.LEAVE_REQUEST, id);
        assertThat(events).extracting(AuditEvent::getEventType).contains("REQUEST_SUBMITTED", "REQUEST_EVALUATED",
                "STATUS_CHANGED", "APPROVAL_ROUTE_CREATED");
    }

    @Test
    void insufficientBalanceIsRejectedWithReasonAndLedgerStaysUntouched() {
        // 22 days available; ask for 10 weekdays twice -> second one cannot be covered after the first reserves
        LeaveSubmissionResult first = leave.submit(submission(ALICE, LP_ANNUAL_US, 11, 2, 11, 13)); // 10 business days
        leave.evaluate(first.request().getLeaveRequestId(), ALICE);
        // 12 more business days but only 12 left? 22-10 = 12 -> still fine; ask 13 to exceed
        LeaveSubmissionResult second = leave.submit(new LeaveSubmission(ALICE, LP_ANNUAL_US, date(2026, 12, 1), date(2026, 12, 18), null, "long", null)); // 14 days
        leave.evaluate(second.request().getLeaveRequestId(), ALICE);
        LeaveRequest r2 = requests.findById(second.request().getLeaveRequestId()).orElseThrow();
        assertThat(r2.getRequestStatus()).isEqualTo(LeaveRequestStatus.REJECTED);
        assertThat(r2.getDecisionReason()).contains(ReasonCode.INSUFFICIENT_BALANCE.name());
        // only the first reservation exists in the ledger; the rejected request moved nothing
        assertThat(balances.history(balance(ALICE, LP_ANNUAL_US).getLeaveBalanceId()))
                .extracting(LeaveBalanceTransaction::getLeaveRequestId).containsOnlyOnce(first.request().getLeaveRequestId());
        assertThat(balance(ALICE, LP_ANNUAL_US).getPendingBalance()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void overlappingApprovedLeaveIsFlaggedAndBothRecordsArePreserved() {
        LeaveSubmissionResult a = leave.submit(submission(ALICE, LP_ANNUAL_US, 11, 9, 11, 13));
        leave.evaluate(a.request().getLeaveRequestId(), ALICE);
        ApprovalStep step = approvals.stepsOf(RequestType.LEAVE, a.request().getLeaveRequestId()).get(0);
        approvals.approve(step.getApprovalStepId(), new ApprovalService.ActionCommand(LEAD, null, "ok"));

        LeaveSubmissionResult b = leave.submit(submission(ALICE, LP_ANNUAL_US, 11, 11, 11, 18));
        leave.evaluate(b.request().getLeaveRequestId(), ALICE);

        LeaveRequest ra = requests.findById(a.request().getLeaveRequestId()).orElseThrow();
        LeaveRequest rb = requests.findById(b.request().getLeaveRequestId()).orElseThrow();
        assertThat(ra.getRequestStatus()).isEqualTo(LeaveRequestStatus.APPROVED);   // untouched
        assertThat(rb.getRequestStatus()).isEqualTo(LeaveRequestStatus.FLAGGED);    // preserved and flagged
        assertThat(rb.getDecisionReason()).contains(ReasonCode.OVERLAPPING_APPROVED_LEAVE.name());
        assertThat(balance(ALICE, LP_ANNUAL_US).getPendingBalance()).isEqualByComparingTo("0.00"); // nothing reserved for the flagged one
    }

    @Test
    void holidaysAndWeekendsDoNotCountAsBusinessDays() {
        // Thanksgiving 2026-11-26 (Thu) is a US holiday: Wed 25 .. Mon 30 = Wed, Fri, Mon = 3 business days
        LeaveSubmissionResult r = leave.submit(submission(ALICE, LP_ANNUAL_US, 11, 25, 11, 30));
        leave.evaluate(r.request().getLeaveRequestId(), ALICE);
        assertThat(requests.findById(r.request().getLeaveRequestId()).orElseThrow().getCalculatedBusinessDays()).isEqualByComparingTo("3");
    }

    @Test
    void ineligibleInsufficientNoticeAndNonWorkingRangesAreRejectedWithExplicitCodes() {
        LeaveRequest contractor = evaluated(submission(CONTRACTOR, LP_ANNUAL_US, 11, 2, 11, 4), CONTRACTOR);
        assertThat(contractor.getDecisionReason()).contains("EMPLOYEE_NOT_ELIGIBLE");

        LeaveRequest shortNotice = evaluated(submission(ALICE, LP_ANNUAL_US, 10, 26, 10, 27), ALICE); // 4 days notice < 7
        assertThat(shortNotice.getDecisionReason()).contains("INSUFFICIENT_NOTICE");

        LeaveRequest weekend = evaluated(submission(ALICE, LP_SICK, 10, 24, 10, 25), ALICE); // Sat-Sun
        assertThat(weekend.getDecisionReason()).contains("HOLIDAY_OR_NON_WORKING_DAY_ONLY");

        LeaveRequest tooLong = evaluated(submission(ALICE, LP_ANNUAL_US, 12, 1, 12, 28), ALICE); // > 15 business days
        assertThat(tooLong.getDecisionReason()).contains("POLICY_LIMIT_EXCEEDED");

        LeaveRequest sickNoDoc = evaluated(submission(ALICE, LP_SICK, 10, 22, 10, 26), ALICE); // 3 business days (22,23,26), > 2 needs doc
        assertThat(sickNoDoc.getRequestStatus()).isEqualTo(LeaveRequestStatus.NEEDS_REVIEW);
        assertThat(sickNoDoc.getDecisionReason()).contains("DOCUMENTATION_MISSING");
    }

    @Test
    void invertedDateRangeIsPreservedAsARejectedRequest() {
        LeaveSubmissionResult r = leave.submit(submission(BOB, LP_ANNUAL_US, 11, 10, 11, 5));
        assertThat(r.accepted()).isTrue();
        LeaveRequest req = requests.findById(r.request().getLeaveRequestId()).orElseThrow();
        assertThat(req.getRequestStatus()).isEqualTo(LeaveRequestStatus.REJECTED);
        assertThat(req.getDecisionReason()).contains("INVALID_DATE_RANGE");
        assertThat(req.getStartDate()).isEqualTo(date(2026, 11, 10)); // original values kept
    }

    @Test
    void unknownEmployeeIsRejectedAtIntakeButPreservedInStaging() {
        LeaveSubmissionResult r = leave.submit(submission("EMP-999999", LP_ANNUAL_US, 11, 2, 11, 6));
        assertThat(r.accepted()).isFalse();
        assertThat(r.request()).isNull();
        assertThat(r.errors()).anyMatch(e -> e.startsWith("EMPLOYEE_NOT_FOUND"));
        assertThat(r.submissionId()).startsWith("SUB-");
    }

    private LeaveRequest evaluated(LeaveSubmission s, String actor) {
        LeaveSubmissionResult r = leave.submit(s);
        leave.evaluate(r.request().getLeaveRequestId(), actor);
        return requests.findById(r.request().getLeaveRequestId()).orElseThrow();
    }
}
