package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** Immutable ledger row: one movement of the balance components. */
@Entity
@Table(name = "leave_balance_transaction")
@Getter
@Setter
@NoArgsConstructor
public class LeaveBalanceTransaction extends PersistableEntity {

    @Id
    @Column(name = "txn_id")
    private String txnId;

    @Column(name = "leave_balance_id")
    private String leaveBalanceId;

    @Column(name = "leave_request_id")
    private String leaveRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type")
    private BalanceTransactionType txnType;

    @Column(name = "period_key")
    private String periodKey;

    @Column(name = "opening_delta")
    private BigDecimal openingDelta = BigDecimal.ZERO;

    @Column(name = "accrued_delta")
    private BigDecimal accruedDelta = BigDecimal.ZERO;

    @Column(name = "carried_forward_delta")
    private BigDecimal carriedForwardDelta = BigDecimal.ZERO;

    @Column(name = "used_delta")
    private BigDecimal usedDelta = BigDecimal.ZERO;

    @Column(name = "pending_delta")
    private BigDecimal pendingDelta = BigDecimal.ZERO;

    @Column(name = "txn_date")
    private LocalDate txnDate;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    private String actor;

    private String reason;

    @Override
    public String getId() {
        return txnId;
    }
}
