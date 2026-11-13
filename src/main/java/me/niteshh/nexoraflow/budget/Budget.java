package me.niteshh.nexoraflow.budget;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.PersistableEntity;

/**
 * Allocated budget of a cost center for one period (month). Only the allocation is stored;
 * consumption and remaining amounts are always derived from source claims.
 */
@Entity
@Table(name = "budget")
@Getter
@Setter
@NoArgsConstructor
public class Budget extends PersistableEntity {

    @Id
    @Column(name = "budget_id")
    private String budgetId;

    @Column(name = "cost_center_id")
    private String costCenterId;

    @Column(name = "fiscal_year")
    private int fiscalYear;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "allocated_amount")
    private BigDecimal allocatedAmount;

    private String currency;

    @Column(name = "commit_pending")
    private boolean commitPending = true;

    @Enumerated(EnumType.STRING)
    private BudgetStatus status = BudgetStatus.ACTIVE;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = AppClock.currentInstant();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = AppClock.currentInstant();
    }

    @Override
    public String getId() {
        return budgetId;
    }

    public boolean covers(LocalDate date) {
        return !date.isBefore(periodStart) && !date.isAfter(periodEnd);
    }
}
