package me.niteshh.nexoraflow.leave;

public enum BalanceTransactionType {
    OPENING_GRANT,
    ACCRUAL,
    CARRY_FORWARD,
    /** Days are reserved (pending) when a request enters the approval workflow. */
    PENDING_RESERVE,
    /** Reservation is released when a request is rejected, cancelled or flagged. */
    PENDING_RELEASE,
    /** Final approval: pending days become used days. Available balance is unchanged. */
    CONSUME,
    /** An approved request is cancelled: used days are given back. */
    RESTORE,
    MANUAL_ADJUSTMENT
}
