package me.niteshh.nexoraflow.leave;

import me.niteshh.nexoraflow.common.lifecycle.StateMachine;

/** Lifecycle of a leave request. Transitions are enforced by {@link #LIFECYCLE}. */
public enum LeaveRequestStatus {
    SUBMITTED,
    PENDING_APPROVAL,
    NEEDS_REVIEW,
    FLAGGED,
    APPROVED,
    REJECTED,
    CANCELLED;

    public static final StateMachine<LeaveRequestStatus> LIFECYCLE = StateMachine
            .builder("LeaveRequest", LeaveRequestStatus.class)
            .from(SUBMITTED, PENDING_APPROVAL, NEEDS_REVIEW, FLAGGED, REJECTED, CANCELLED)
            .from(NEEDS_REVIEW, PENDING_APPROVAL, REJECTED, CANCELLED)
            .from(FLAGGED, PENDING_APPROVAL, REJECTED, CANCELLED)
            .from(PENDING_APPROVAL, APPROVED, REJECTED, FLAGGED, CANCELLED)
            .from(APPROVED, CANCELLED)
            .from(REJECTED)
            .from(CANCELLED)
            .build();

    public boolean isTerminal() {
        return LIFECYCLE.isTerminal(this);
    }
}
