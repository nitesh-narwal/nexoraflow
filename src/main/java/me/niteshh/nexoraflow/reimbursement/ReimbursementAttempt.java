package me.niteshh.nexoraflow.reimbursement;

import java.time.Instant;

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

/** Every attempt to create a reimbursement, including those the system rejected. */
@Entity
@Table(name = "reimbursement_attempt")
@Getter
@Setter
@NoArgsConstructor
public class ReimbursementAttempt extends PersistableEntity {

    @Id
    @Column(name = "attempt_id")
    private String attemptId;

    @Column(name = "expense_claim_id")
    private String expenseClaimId;

    @Column(name = "attempted_at")
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    private AttemptOutcome outcome;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "reimbursement_id")
    private String reimbursementId;

    @Column(name = "requested_by")
    private String requestedBy;

    private String details;

    @Override
    public String getId() {
        return attemptId;
    }
}
