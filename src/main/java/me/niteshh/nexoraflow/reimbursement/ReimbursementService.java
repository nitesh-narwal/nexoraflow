package me.niteshh.nexoraflow.reimbursement;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApprovalStepRepository;
import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.WorkflowResult;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.expense.EligibilityStatus;
import me.niteshh.nexoraflow.expense.ExpenseClaim;
import me.niteshh.nexoraflow.expense.ExpenseClaimRepository;
import me.niteshh.nexoraflow.expense.ExpenseClaimStatus;

/**
 * Creates reimbursements - only for eligible, finally approved claims, and at most once per claim.
 *
 * <p>Duplicate prevention is layered: (1) the claim row is locked so concurrent requests are serialised;
 * (2) an existing reimbursement turns the request into a recorded DUPLICATE_REJECTED attempt (idempotent
 * outcome); (3) a unique constraint on expense_claim_id and (4) a database trigger that only accepts
 * eligible, fully approved claims stay as the last line of defence.
 */
@Service
public class ReimbursementService {

    private final ReimbursementRepository reimbursements;
    private final ReimbursementAttemptRepository attempts;
    private final ExpenseClaimRepository claims;
    private final ApprovalStepRepository steps;
    private final IdService ids;
    private final AppClock clock;
    private final AuditService audit;

    public ReimbursementService(ReimbursementRepository reimbursements, ReimbursementAttemptRepository attempts,
                                ExpenseClaimRepository claims, ApprovalStepRepository steps, IdService ids,
                                AppClock clock, AuditService audit) {
        this.reimbursements = reimbursements;
        this.attempts = attempts;
        this.claims = claims;
        this.steps = steps;
        this.ids = ids;
        this.clock = clock;
        this.audit = audit;
    }

    public Reimbursement require(String reimbursementId) {
        return reimbursements.findById(reimbursementId)
                .orElseThrow(() -> NexoraException.notFound("Reimbursement", reimbursementId));
    }

    /** Idempotent reimbursement request for a claim. Safe to call any number of times. */
    @Transactional
    public ReimbursementResult requestReimbursement(String claimId, String requestedBy) {
        ExpenseClaim claim = claims.findByIdForUpdate(claimId)
                .orElseThrow(() -> NexoraException.notFound("Expense claim", claimId));

        var existing = reimbursements.findByExpenseClaimId(claimId);
        if (existing.isPresent()) {
            String message = "Claim " + claimId + " already has reimbursement " + existing.get().getReimbursementId();
            recordAttempt(claimId, AttemptOutcome.DUPLICATE_REJECTED, ReasonCode.DUPLICATE_REIMBURSEMENT,
                    existing.get().getReimbursementId(), requestedBy, message);
            audit.record(AuditCategory.VIOLATION, EntityType.REIMBURSEMENT, existing.get().getReimbursementId(),
                    AuditEventType.REIMBURSEMENT_DUPLICATE_REJECTED, existing.get().getReimbursementStatus().name(),
                    existing.get().getReimbursementStatus().name(), requestedBy, ReasonCode.DUPLICATE_REIMBURSEMENT.name(),
                    Map.of("expenseClaimId", claimId));
            return new ReimbursementResult(AttemptOutcome.DUPLICATE_REJECTED, existing.get(),
                    ReasonCode.DUPLICATE_REIMBURSEMENT, message);
        }

        String blocker = eligibilityBlocker(claim);
        if (blocker != null) {
            recordAttempt(claimId, AttemptOutcome.NOT_ELIGIBLE_REJECTED, ReasonCode.CLAIM_NOT_REIMBURSABLE, null, requestedBy, blocker);
            audit.record(AuditCategory.VIOLATION, EntityType.EXPENSE_CLAIM, claimId,
                    AuditEventType.REIMBURSEMENT_NOT_ELIGIBLE_REJECTED, claim.getClaimStatus().name(),
                    claim.getClaimStatus().name(), requestedBy, ReasonCode.CLAIM_NOT_REIMBURSABLE.name(), Map.of("message", blocker));
            return new ReimbursementResult(AttemptOutcome.NOT_ELIGIBLE_REJECTED, null, ReasonCode.CLAIM_NOT_REIMBURSABLE, blocker);
        }

        Reimbursement r = new Reimbursement();
        r.setReimbursementId(ids.reimbursementId(clock.today()));
        r.setExpenseClaimId(claimId);
        r.setEmployeeId(claim.getEmployeeId());
        r.setApprovedAmount(claim.getTotalAmount());
        r.setCurrency(claim.getCurrency());
        r.setReimbursementStatus(ReimbursementStatus.PENDING_PAYMENT);
        r.setApprovedAt(clock.now());
        reimbursements.saveAndFlush(r);
        recordAttempt(claimId, AttemptOutcome.CREATED, null, r.getReimbursementId(), requestedBy, "Reimbursement created");
        audit.record(AuditCategory.FINANCIAL, EntityType.REIMBURSEMENT, r.getReimbursementId(),
                AuditEventType.REIMBURSEMENT_CREATED, null, ReimbursementStatus.PENDING_PAYMENT.name(), requestedBy,
                "Claim finally approved", Map.of("expenseClaimId", claimId, "amount", r.getApprovedAmount(),
                        "currency", r.getCurrency()));
        return new ReimbursementResult(AttemptOutcome.CREATED, r, null, "Reimbursement created");
    }

    /** Simulates the payroll run paying the reimbursement. */
    @Transactional
    public WorkflowResult<Reimbursement> markPaid(String reimbursementId, String actor) {
        Reimbursement r = require(reimbursementId);
        if (!ReimbursementStatus.LIFECYCLE.canTransition(r.getReimbursementStatus(), ReimbursementStatus.PAID)) {
            audit.record(AuditCategory.VIOLATION, EntityType.REIMBURSEMENT, reimbursementId,
                    AuditEventType.INVALID_TRANSITION_REJECTED, r.getReimbursementStatus().name(),
                    r.getReimbursementStatus().name(), actor, ReasonCode.INVALID_STATE_TRANSITION.name(),
                    Map.of("attemptedAction", "MARK_PAID"));
            return WorkflowResult.rejected(ReasonCode.INVALID_STATE_TRANSITION,
                    "Reimbursement is " + r.getReimbursementStatus() + " and cannot be paid");
        }
        ReimbursementStatus previous = r.getReimbursementStatus();
        r.setReimbursementStatus(ReimbursementStatus.PAID);
        r.setProcessedAt(clock.now());
        r.setPaymentReference(ids.paymentReference(clock.today()));
        reimbursements.saveAndFlush(r);
        audit.record(AuditCategory.FINANCIAL, EntityType.REIMBURSEMENT, reimbursementId, AuditEventType.REIMBURSEMENT_PAID,
                previous.name(), ReimbursementStatus.PAID.name(), actor, null,
                Map.of("paymentReference", r.getPaymentReference()));
        return WorkflowResult.accepted(r);
    }

    /** Why a claim cannot be reimbursed yet, or null when it can. */
    private String eligibilityBlocker(ExpenseClaim claim) {
        if (claim.getClaimStatus() != ExpenseClaimStatus.APPROVED) {
            return "Claim is " + claim.getClaimStatus() + ", not APPROVED";
        }
        if (claim.getEligibilityStatus() != EligibilityStatus.ELIGIBLE) {
            return "Claim eligibility is " + claim.getEligibilityStatus();
        }
        var all = steps.findByExpenseClaimIdOrderByStepSequence(claim.getExpenseClaimId());
        boolean open = all.stream().anyMatch(s -> s.getApprovalStatus() == ApprovalStatus.WAITING
                || s.getApprovalStatus() == ApprovalStatus.PENDING || s.getApprovalStatus() == ApprovalStatus.REJECTED);
        boolean approvedAtLeastOne = all.stream().anyMatch(s -> s.getApprovalStatus() == ApprovalStatus.APPROVED);
        if (open || !approvedAtLeastOne) {
            return "Approval steps are not all completed";
        }
        return null;
    }

    private void recordAttempt(String claimId, AttemptOutcome outcome, ReasonCode code, String reimbursementId,
                               String requestedBy, String details) {
        ReimbursementAttempt a = new ReimbursementAttempt();
        a.setAttemptId(ids.reimbursementAttemptId());
        a.setExpenseClaimId(claimId);
        a.setAttemptedAt(clock.now());
        a.setOutcome(outcome);
        a.setReasonCode(code == null ? null : code.name());
        a.setReimbursementId(reimbursementId);
        a.setRequestedBy(requestedBy == null ? AuditService.SYSTEM_ACTOR : requestedBy);
        a.setDetails(details);
        attempts.save(a);
    }

    public List<ReimbursementAttempt> attemptsFor(String claimId) {
        return attempts.findByExpenseClaimIdOrderByAttemptedAtAscAttemptIdAsc(claimId);
    }
}
