package me.niteshh.nexoraflow.expense;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.EntityType;

/** The only place that changes an expense claim's status: checks the lifecycle and writes the audit event. */
@Component
public class ExpenseStatusUpdater {

    private final ExpenseClaimRepository claims;
    private final AuditService audit;

    public ExpenseStatusUpdater(ExpenseClaimRepository claims, AuditService audit) {
        this.claims = claims;
        this.audit = audit;
    }

    public void transition(ExpenseClaim claim, ExpenseClaimStatus to, Decision decision, EligibilityStatus eligibility,
                           String reasonCodes, String actor, String auditReason, Map<String, Object> metadata) {
        ExpenseClaimStatus from = claim.getClaimStatus();
        if (!ExpenseClaimStatus.LIFECYCLE.canTransition(from, to)) {
            throw new IllegalStateException("Illegal expense transition " + from + " -> " + to + " for " + claim.getExpenseClaimId());
        }
        claim.setClaimStatus(to);
        claim.setDecision(decision);
        claim.setDecisionReason(reasonCodes);
        if (eligibility != null) {
            claim.setEligibilityStatus(eligibility);
        }
        claims.saveAndFlush(claim);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (metadata != null) {
            meta.putAll(metadata);
        }
        audit.record(AuditCategory.WORKFLOW, EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                AuditEventType.STATUS_CHANGED, from.name(), to.name(), actor, auditReason, meta);
    }
}
