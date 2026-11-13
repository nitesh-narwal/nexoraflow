package me.niteshh.nexoraflow.approval;

import me.niteshh.nexoraflow.common.lifecycle.StateMachine;

/** Lifecycle of one approval step. Terminal states are immutable (also enforced by a DB trigger). */
public enum ApprovalStatus {
    /** Created but not yet active: an earlier step must complete first. */
    WAITING,
    /** Active: assigned to the approver and awaiting action. */
    PENDING,
    APPROVED,
    REJECTED,
    /** Cancelled because the request was rejected, flagged or withdrawn. */
    CANCELLED,
    /** Handed over to the approver's own manager; a replacement step is created. */
    ESCALATED,
    /** Not required (e.g. the same approver already approved an earlier level). */
    SKIPPED;

    public static final StateMachine<ApprovalStatus> LIFECYCLE = StateMachine
            .builder("ApprovalStep", ApprovalStatus.class)
            .from(WAITING, PENDING, CANCELLED, SKIPPED)
            .from(PENDING, APPROVED, REJECTED, CANCELLED, ESCALATED)
            .from(APPROVED)
            .from(REJECTED)
            .from(CANCELLED)
            .from(ESCALATED)
            .from(SKIPPED)
            .build();

    public boolean isTerminal() {
        return LIFECYCLE.isTerminal(this);
    }
}
