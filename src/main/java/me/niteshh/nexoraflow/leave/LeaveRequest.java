package me.niteshh.nexoraflow.leave;

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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "leave_request")
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequest extends PersistableEntity {

    @Id
    @Column(name = "leave_request_id")
    private String leaveRequestId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "leave_policy_id")
    private String leavePolicyId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** Days as stated by the requester (calendar span unless provided). Informational only. */
    @Column(name = "requested_days")
    private BigDecimal requestedDays;

    private String reason;

    @Column(name = "submission_date")
    private LocalDate submissionDate;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_status")
    private LeaveRequestStatus requestStatus = LeaveRequestStatus.SUBMITTED;

    /** Authoritative duration computed by the engine from weekends and public holidays. */
    @Column(name = "calculated_business_days")
    private BigDecimal calculatedBusinessDays;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "supporting_document_ref")
    private String supportingDocumentRef;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

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
        return leaveRequestId;
    }
}
