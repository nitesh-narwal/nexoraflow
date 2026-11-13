package me.niteshh.nexoraflow.expense;

import me.niteshh.nexoraflow.common.lifecycle.StateMachine;

/** Lifecycle of an expense claim. Reimbursement state is tracked separately on the reimbursement. */
public enum ExpenseClaimStatus {
    SUBMITTED,
    PENDING_APPROVAL,
    NEEDS_REVIEW,
    FLAGGED,
    ON_HOLD,
    APPROVED,
    REJECTED,
    CANCELLED;

    public static final StateMachine<ExpenseClaimStatus> LIFECYCLE = StateMachine
            .builder("ExpenseClaim", ExpenseClaimStatus.class)
            .from(SUBMITTED, PENDING_APPROVAL, NEEDS_REVIEW, FLAGGED, ON_HOLD, REJECTED, CANCELLED)
            .from(NEEDS_REVIEW, PENDING_APPROVAL, REJECTED, CANCELLED)
            .from(FLAGGED, PENDING_APPROVAL, REJECTED, CANCELLED)
            .from(ON_HOLD, PENDING_APPROVAL, REJECTED, CANCELLED)
            .from(PENDING_APPROVAL, APPROVED, REJECTED, FLAGGED, ON_HOLD, CANCELLED)
            .from(APPROVED)
            .from(REJECTED)
            .from(CANCELLED)
            .build();
}
