package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApprovalStep;
import me.niteshh.nexoraflow.approval.ApprovalStepRepository;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.expense.EligibilityStatus;
import me.niteshh.nexoraflow.expense.ExpenseClaim;
import me.niteshh.nexoraflow.expense.ExpenseClaimRepository;
import me.niteshh.nexoraflow.expense.ExpenseClaimStatus;
import me.niteshh.nexoraflow.reimbursement.AttemptOutcome;
import me.niteshh.nexoraflow.reimbursement.Reimbursement;
import me.niteshh.nexoraflow.reimbursement.ReimbursementAttempt;
import me.niteshh.nexoraflow.reimbursement.ReimbursementAttemptRepository;
import me.niteshh.nexoraflow.reimbursement.ReimbursementRepository;
import me.niteshh.nexoraflow.reimbursement.ReimbursementResult;
import me.niteshh.nexoraflow.reimbursement.ReimbursementService;
import me.niteshh.nexoraflow.reimbursement.ReimbursementStatus;

class ReimbursementServiceTest {

    private ReimbursementRepository reimbursements;
    private ReimbursementAttemptRepository attempts;
    private ExpenseClaimRepository claims;
    private ApprovalStepRepository steps;
    private ReimbursementService service;
    private ExpenseClaim claim;

    @BeforeEach
    void setUp() {
        AppClock.setSimulatedTime(Instant.parse("2026-10-22T10:00:00Z"));
        reimbursements = mock(ReimbursementRepository.class);
        attempts = mock(ReimbursementAttemptRepository.class);
        claims = mock(ExpenseClaimRepository.class);
        steps = mock(ApprovalStepRepository.class);
        IdService ids = mock(IdService.class);
        when(ids.reimbursementId(any())).thenReturn("RMB-2026-000001");
        when(ids.reimbursementAttemptId()).thenReturn("RMA-000001");
        service = new ReimbursementService(reimbursements, attempts, claims, steps, ids, new AppClock(), mock(AuditService.class));

        claim = new ExpenseClaim();
        claim.setExpenseClaimId("EXP-2026-000001");
        claim.setEmployeeId("EMP-000001");
        claim.setCurrency("USD");
        claim.setTotalAmount(new BigDecimal("120.00"));
        claim.setClaimStatus(ExpenseClaimStatus.APPROVED);
        claim.setEligibilityStatus(EligibilityStatus.ELIGIBLE);
        when(claims.findByIdForUpdate("EXP-2026-000001")).thenReturn(Optional.of(claim));
        ApprovalStep approved = new ApprovalStep();
        approved.setApprovalStatus(ApprovalStatus.APPROVED);
        when(steps.findByExpenseClaimIdOrderByStepSequence("EXP-2026-000001")).thenReturn(List.of(approved));
        when(reimbursements.findByExpenseClaimId("EXP-2026-000001")).thenReturn(Optional.empty());
    }

    @Test
    void anApprovedEligibleClaimGetsExactlyOneReimbursement() {
        ReimbursementResult r = service.requestReimbursement("EXP-2026-000001", "PAYROLL");
        assertThat(r.created()).isTrue();
        assertThat(r.reimbursement().getApprovedAmount()).isEqualByComparingTo("120.00");
        assertThat(r.reimbursement().getReimbursementStatus()).isEqualTo(ReimbursementStatus.PENDING_PAYMENT);
        verify(reimbursements).saveAndFlush(any(Reimbursement.class));
    }

    @Test
    void aSecondRequestIsRejectedRecordedAndCreatesNothing() {
        Reimbursement existing = new Reimbursement();
        existing.setReimbursementId("RMB-2026-000001");
        existing.setReimbursementStatus(ReimbursementStatus.PENDING_PAYMENT);
        when(reimbursements.findByExpenseClaimId("EXP-2026-000001")).thenReturn(Optional.of(existing));

        ReimbursementResult r = service.requestReimbursement("EXP-2026-000001", "PAYROLL");
        assertThat(r.outcome()).isEqualTo(AttemptOutcome.DUPLICATE_REJECTED);
        assertThat(r.reasonCode()).isEqualTo(ReasonCode.DUPLICATE_REIMBURSEMENT);
        verify(reimbursements, never()).saveAndFlush(any());
        org.mockito.ArgumentCaptor<ReimbursementAttempt> captor = org.mockito.ArgumentCaptor.forClass(ReimbursementAttempt.class);
        verify(attempts).save(captor.capture());
        assertThat(captor.getValue().getOutcome()).isEqualTo(AttemptOutcome.DUPLICATE_REJECTED);
        assertThat(captor.getValue().getReimbursementId()).isEqualTo("RMB-2026-000001");
    }

    @Test
    void claimsThatAreNotApprovedAreNeverReimbursed() {
        claim.setClaimStatus(ExpenseClaimStatus.PENDING_APPROVAL);
        ReimbursementResult r = service.requestReimbursement("EXP-2026-000001", "PAYROLL");
        assertThat(r.outcome()).isEqualTo(AttemptOutcome.NOT_ELIGIBLE_REJECTED);
        verify(reimbursements, never()).saveAndFlush(any());
    }

    @Test
    void ineligibleClaimsAndClaimsWithOpenStepsAreRefused() {
        claim.setEligibilityStatus(EligibilityStatus.REVIEW_REQUIRED);
        assertThat(service.requestReimbursement("EXP-2026-000001", "PAYROLL").outcome()).isEqualTo(AttemptOutcome.NOT_ELIGIBLE_REJECTED);
        claim.setEligibilityStatus(EligibilityStatus.ELIGIBLE);
        ApprovalStep open = new ApprovalStep();
        open.setApprovalStatus(ApprovalStatus.PENDING);
        when(steps.findByExpenseClaimIdOrderByStepSequence("EXP-2026-000001")).thenReturn(List.of(open));
        assertThat(service.requestReimbursement("EXP-2026-000001", "PAYROLL").outcome()).isEqualTo(AttemptOutcome.NOT_ELIGIBLE_REJECTED);
    }

    @Test
    void unknownClaimsAreReportedAsNotFound() {
        when(claims.findByIdForUpdate("EXP-0")).thenReturn(Optional.empty());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requestReimbursement("EXP-0", "PAYROLL"))
                .isInstanceOf(NexoraException.class);
    }

    @Test
    void paymentStateTransitionsFollowTheLifecycle() {
        assertThat(ReimbursementStatus.LIFECYCLE.canTransition(ReimbursementStatus.PENDING_PAYMENT, ReimbursementStatus.PAID)).isTrue();
        assertThat(ReimbursementStatus.LIFECYCLE.canTransition(ReimbursementStatus.PAID, ReimbursementStatus.PENDING_PAYMENT)).isFalse();
        assertThat(ReimbursementStatus.LIFECYCLE.isTerminal(ReimbursementStatus.PAID)).isTrue();
    }
}
