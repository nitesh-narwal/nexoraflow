package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "expense_claim")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseClaim extends PersistableEntity {

    @Id
    @Column(name = "expense_claim_id")
    private String expenseClaimId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "claim_date")
    private LocalDate claimDate;

    @Column(name = "expense_period_start")
    private LocalDate expensePeriodStart;

    @Column(name = "expense_period_end")
    private LocalDate expensePeriodEnd;

    @Column(name = "submission_date")
    private LocalDate submissionDate;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** Canonical, FK-valid cost center. Null when the submitted reference was missing or invalid. */
    @Column(name = "cost_center_id")
    private String costCenterId;

    /** The cost-center reference exactly as submitted, preserved for audit. */
    @Column(name = "submitted_cost_center_ref")
    private String submittedCostCenterRef;

    private String currency;

    @Column(name = "total_amount")
    private BigDecimal totalAmount = Money.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_status")
    private ExpenseClaimStatus claimStatus = ExpenseClaimStatus.SUBMITTED;

    @Enumerated(EnumType.STRING)
    @Column(name = "eligibility_status")
    private EligibilityStatus eligibilityStatus = EligibilityStatus.NOT_EVALUATED;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "claim", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @OrderBy("lineNumber ASC")
    private List<ExpenseLine> lines = new ArrayList<>();

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
        return expenseClaimId;
    }

    public void addLine(ExpenseLine line) {
        line.setClaim(this);
        lines.add(line);
    }

    /**
     * Claim total is always derived from its line items, never set independently: the sum of the line
     * amounts that are positive. A non-positive line is invalid by definition and cannot be part of a claim total.
     */
    public BigDecimal sumOfLines() {
        BigDecimal total = Money.ZERO;
        for (ExpenseLine line : lines) {
            if (line.getAmount().signum() > 0) {
                total = total.add(line.getAmount());
            }
        }
        return Money.scale(total);
    }

    public void recalculateTotal() {
        this.totalAmount = sumOfLines();
    }
}
