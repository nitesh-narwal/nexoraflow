package me.niteshh.nexoraflow.policy;

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

@Entity
@Table(name = "expense_policy")
@Getter
@Setter
@NoArgsConstructor
public class ExpensePolicy extends PersistableEntity {

    @Id
    @Column(name = "expense_policy_id")
    private String expensePolicyId;

    @Column(name = "policy_name")
    private String policyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_category")
    private ExpenseCategory expenseCategory;

    private String country;

    @Enumerated(EnumType.STRING)
    @Column(name = "employee_type")
    private PolicyEmployeeType employeeType = PolicyEmployeeType.ALL;

    private String currency;

    @Column(name = "maximum_per_transaction")
    private BigDecimal maximumPerTransaction;

    @Column(name = "maximum_daily_amount")
    private BigDecimal maximumDailyAmount;

    @Column(name = "maximum_monthly_amount")
    private BigDecimal maximumMonthlyAmount;

    @Column(name = "receipt_required")
    private boolean receiptRequired;

    /** A receipt is required for amounts strictly greater than this threshold (policy currency). */
    @Column(name = "receipt_threshold")
    private BigDecimal receiptThreshold = BigDecimal.ZERO;

    @Column(name = "requires_manager_approval")
    private boolean requiresManagerApproval = true;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_status")
    private PolicyStatus policyStatus = PolicyStatus.ACTIVE;

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
        return expensePolicyId;
    }

    public boolean isEffectiveOn(LocalDate date) {
        return policyStatus == PolicyStatus.ACTIVE && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}
