package me.niteshh.nexoraflow.reimbursement;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "reimbursement")
@Getter
@Setter
@NoArgsConstructor
public class Reimbursement extends PersistableEntity {

    @Id
    @Column(name = "reimbursement_id")
    private String reimbursementId;

    @Column(name = "expense_claim_id")
    private String expenseClaimId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;

    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reimbursement_status")
    private ReimbursementStatus reimbursementStatus = ReimbursementStatus.PENDING_PAYMENT;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

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
        return reimbursementId;
    }
}
