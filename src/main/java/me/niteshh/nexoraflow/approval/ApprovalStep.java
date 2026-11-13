package me.niteshh.nexoraflow.approval;

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
import me.niteshh.nexoraflow.common.RequestType;

/** One approval step of a request. Approval history is a set of separate, immutable-once-complete rows. */
@Entity
@Table(name = "approval_step")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalStep extends PersistableEntity {

    @Id
    @Column(name = "approval_step_id")
    private String approvalStepId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type")
    private RequestType requestType;

    @Column(name = "leave_request_id")
    private String leaveRequestId;

    @Column(name = "expense_claim_id")
    private String expenseClaimId;

    /** Generated column mirroring whichever request FK is populated. */
    @Column(name = "request_id", insertable = false, updatable = false)
    private String requestId;

    @Column(name = "step_sequence")
    private int stepSequence;

    @Column(name = "approval_level")
    private int approvalLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_role")
    private ApproverRole approverRole;

    @Column(name = "approver_employee_id")
    private String approverEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status")
    private ApprovalStatus approvalStatus = ApprovalStatus.WAITING;

    @Column(name = "approval_rule_id")
    private String approvalRuleId;

    @Column(name = "escalated_from_step_id")
    private String escalatedFromStepId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "action_at")
    private Instant actionAt;

    private String comments;

    @Column(name = "decision_reason")
    private String decisionReason;

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
        return approvalStepId;
    }

    /** The business ID of the request this step belongs to, regardless of type. */
    public String targetRequestId() {
        return requestType == RequestType.LEAVE ? leaveRequestId : expenseClaimId;
    }
}
