package me.niteshh.nexoraflow.common;

/** Outcome vocabulary shared by leave requests and expense claims. */
public enum Decision {
    APPROVED,
    REJECTED,
    PENDING_APPROVAL,
    NEEDS_REVIEW,
    FLAGGED,
    ON_HOLD,
    CANCELLED
}
