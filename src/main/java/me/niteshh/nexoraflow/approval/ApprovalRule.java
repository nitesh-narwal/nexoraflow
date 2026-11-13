package me.niteshh.nexoraflow.approval;

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
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

/**
 * Configurable approval authority rule. For EXPENSE the amount range is USD-equivalent; for LEAVE it is
 * the number of business days. Range convention: {@code minimumAmount < amount <= maximumAmount}.
 */
@Entity
@Table(name = "approval_rule")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalRule extends PersistableEntity {

    public enum Status { ACTIVE, INACTIVE }

    @Id
    @Column(name = "approval_rule_id")
    private String approvalRuleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_type")
    private RequestType workflowType;

    private String country;

    @Column(name = "department_id")
    private String departmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_category")
    private ExpenseCategory expenseCategory;

    @Column(name = "minimum_amount")
    private BigDecimal minimumAmount = BigDecimal.ZERO;

    @Column(name = "maximum_amount")
    private BigDecimal maximumAmount;

    @Column(name = "amount_unit")
    private String amountUnit;

    @Column(name = "approval_level")
    private int approvalLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role")
    private ApproverRole approverRole;

    @Column(name = "approver_designation")
    private String approverDesignation;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    private int priority = 100;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_status")
    private Status ruleStatus = Status.ACTIVE;

    private String description;

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
        return approvalRuleId;
    }

    /** Number of non-null scope filters; used as the specificity tie-breaker. */
    public int specificity() {
        int n = 0;
        if (country != null) {
            n++;
        }
        if (departmentId != null) {
            n++;
        }
        if (expenseCategory != null) {
            n++;
        }
        return n;
    }
}
