package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

/**
 * Balance of one employee for one leave policy and year. {@code availableBalance} is a database
 * GENERATED column: opening + accrued + carried forward - used - pending. It is never written.
 */
@Entity
@Table(name = "leave_balance")
@Getter
@Setter
@NoArgsConstructor
public class LeaveBalance extends PersistableEntity {

    @Id
    @Column(name = "leave_balance_id")
    private String leaveBalanceId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "leave_policy_id")
    private String leavePolicyId;

    @Column(name = "balance_year")
    private int balanceYear;

    @Column(name = "opening_balance")
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "accrued_balance")
    private BigDecimal accruedBalance = BigDecimal.ZERO;

    @Column(name = "carried_forward_balance")
    private BigDecimal carriedForwardBalance = BigDecimal.ZERO;

    @Column(name = "used_balance")
    private BigDecimal usedBalance = BigDecimal.ZERO;

    @Column(name = "pending_balance")
    private BigDecimal pendingBalance = BigDecimal.ZERO;

    @Column(name = "available_balance", insertable = false, updatable = false)
    private BigDecimal availableBalance;

    @Column(name = "last_updated_at")
    private Instant lastUpdatedAt;

    @Version
    private long version;

    @Override
    public String getId() {
        return leaveBalanceId;
    }

    /** The same formula as the generated column, for in-memory use before the row is re-read. */
    public BigDecimal computeAvailable() {
        return openingBalance.add(accruedBalance).add(carriedForwardBalance)
                .subtract(usedBalance).subtract(pendingBalance);
    }
}
