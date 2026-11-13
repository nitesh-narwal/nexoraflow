package me.niteshh.nexoraflow.expense;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.approval.ApprovalOutcomeHandler;
import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.budget.BudgetService;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.configuration.CurrencyService;
import me.niteshh.nexoraflow.reimbursement.ReimbursementService;

/**
 * Applies the consequences of the approval workflow to an expense claim. Budget is re-checked at final
 * approval (other claims may have consumed it since evaluation) and the reimbursement is created only after
 * the claim has been finally approved.
 */
@Component
public class ExpenseApprovalHandler implements ApprovalOutcomeHandler {

    private final ExpenseClaimRepository claims;
    private final ExpenseStatusUpdater status;
    private final BudgetService budgets;
    private final CurrencyService currencies;
    private final ReimbursementService reimbursements;
    private final AuditService audit;

    public ExpenseApprovalHandler(ExpenseClaimRepository claims, ExpenseStatusUpdater status, BudgetService budgets,
                                  CurrencyService currencies, ReimbursementService reimbursements, AuditService audit) {
        this.claims = claims;
        this.status = status;
        this.budgets = budgets;
        this.currencies = currencies;
        this.reimbursements = reimbursements;
        this.audit = audit;
    }

    @Override
    public RequestType requestType() {
        return RequestType.EXPENSE;
    }

    @Override
    public Optional<String> requesterOf(String requestId) {
        return claims.findById(requestId).map(ExpenseClaim::getEmployeeId);
    }

    @Override
    public boolean awaitingApproval(String requestId) {
        return claims.findById(requestId).map(c -> c.getClaimStatus() == ExpenseClaimStatus.PENDING_APPROVAL).orElse(false);
    }

    @Override
    @Transactional
    public FinalApprovalOutcome onFinalApproval(String requestId, String actor) {
        ExpenseClaim claim = claims.findById(requestId).orElseThrow();
        var position = budgets.position(claim.getCostCenterId(), claim.getClaimDate(), claim.getExpenseClaimId());
        boolean enough = position.isPresent() && currencies.convert(claim.getTotalAmount(), claim.getCurrency(),
                position.get().currency()).compareTo(position.get().remaining()) <= 0;
        if (!enough) {
            status.transition(claim, ExpenseClaimStatus.ON_HOLD, Decision.ON_HOLD, EligibilityStatus.REVIEW_REQUIRED,
                    ReasonCode.INSUFFICIENT_BUDGET.name(), actor, "Final approval blocked: budget no longer sufficient",
                    Map.of("remaining", position.map(p -> p.remaining().toString()).orElse("no budget")));
            audit.record(AuditCategory.VIOLATION, EntityType.EXPENSE_CLAIM, requestId, AuditEventType.FINAL_APPROVAL_BLOCKED,
                    ExpenseClaimStatus.PENDING_APPROVAL.name(), ExpenseClaimStatus.ON_HOLD.name(), actor,
                    ReasonCode.INSUFFICIENT_BUDGET.name(), Map.of());
            return FinalApprovalOutcome.blocked(ReasonCode.INSUFFICIENT_BUDGET, "Budget no longer sufficient at final approval");
        }
        status.transition(claim, ExpenseClaimStatus.APPROVED, Decision.APPROVED, EligibilityStatus.ELIGIBLE, null, actor,
                "All required approvals completed", Map.of());
        reimbursements.requestReimbursement(requestId, actor);
        return FinalApprovalOutcome.ok();
    }

    @Override
    @Transactional
    public void onRejected(String requestId, String actor, String reason) {
        ExpenseClaim claim = claims.findById(requestId).orElseThrow();
        status.transition(claim, ExpenseClaimStatus.REJECTED, Decision.REJECTED, EligibilityStatus.INELIGIBLE,
                ReasonCode.REJECTED_BY_APPROVER.name(), actor, reason, Map.of());
    }
}
