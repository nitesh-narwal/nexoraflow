package me.niteshh.nexoraflow.intake;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
import me.niteshh.nexoraflow.common.RequestType;

/**
 * Staging record: the submission exactly as received, plus the intake verdict. Invalid references are
 * kept here instead of corrupting the canonical relational tables.
 */
@Entity
@Table(name = "submission_record")
@Getter
@Setter
@NoArgsConstructor
public class SubmissionRecord extends PersistableEntity {

    @Id
    @Column(name = "submission_id")
    private String submissionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type")
    private RequestType requestType;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "submitted_by_ref")
    private String submittedByRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(name = "intake_status")
    private IntakeStatus intakeStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors", columnDefinition = "jsonb")
    private String validationErrors;

    @Column(name = "leave_request_id")
    private String leaveRequestId;

    @Column(name = "expense_claim_id")
    private String expenseClaimId;

    @Override
    public String getId() {
        return submissionId;
    }
}
