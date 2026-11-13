package me.niteshh.nexoraflow.reimbursement;

import me.niteshh.nexoraflow.common.lifecycle.StateMachine;

public enum ReimbursementStatus {
    PENDING_PAYMENT,
    PAID,
    FAILED,
    CANCELLED;

    public static final StateMachine<ReimbursementStatus> LIFECYCLE = StateMachine
            .builder("Reimbursement", ReimbursementStatus.class)
            .from(PENDING_PAYMENT, PAID, FAILED, CANCELLED)
            .from(FAILED, PENDING_PAYMENT, CANCELLED)
            .from(PAID)
            .from(CANCELLED)
            .build();
}
