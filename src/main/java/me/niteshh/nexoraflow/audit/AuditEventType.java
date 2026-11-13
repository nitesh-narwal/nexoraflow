package me.niteshh.nexoraflow.audit;

/** Event-type vocabulary for the audit trail. */
public final class AuditEventType {

    public static final String SUBMISSION_RECEIVED = "SUBMISSION_RECEIVED";
    public static final String SUBMISSION_REJECTED_AT_INTAKE = "SUBMISSION_REJECTED_AT_INTAKE";

    public static final String REQUEST_SUBMITTED = "REQUEST_SUBMITTED";
    public static final String REQUEST_EVALUATED = "REQUEST_EVALUATED";
    public static final String STATUS_CHANGED = "STATUS_CHANGED";
    public static final String REQUEST_CANCELLED = "REQUEST_CANCELLED";
    public static final String REVIEW_RESOLVED = "REVIEW_RESOLVED";
    public static final String INVALID_TRANSITION_REJECTED = "INVALID_TRANSITION_REJECTED";

    public static final String APPROVAL_ROUTE_CREATED = "APPROVAL_ROUTE_CREATED";
    public static final String APPROVAL_STEP_ACTIVATED = "APPROVAL_STEP_ACTIVATED";
    public static final String APPROVAL_STEP_APPROVED = "APPROVAL_STEP_APPROVED";
    public static final String APPROVAL_STEP_REJECTED = "APPROVAL_STEP_REJECTED";
    public static final String APPROVAL_STEP_ESCALATED = "APPROVAL_STEP_ESCALATED";
    public static final String APPROVAL_STEP_CANCELLED = "APPROVAL_STEP_CANCELLED";
    public static final String APPROVAL_ATTEMPT_REJECTED = "APPROVAL_ATTEMPT_REJECTED";
    public static final String APPROVER_ESCALATED_UNAVAILABLE = "APPROVER_ESCALATED_UNAVAILABLE";

    public static final String BALANCE_RESERVED = "BALANCE_RESERVED";
    public static final String BALANCE_RELEASED = "BALANCE_RELEASED";
    public static final String BALANCE_CONSUMED = "BALANCE_CONSUMED";
    public static final String BALANCE_RESTORED = "BALANCE_RESTORED";

    public static final String REIMBURSEMENT_CREATED = "REIMBURSEMENT_CREATED";
    public static final String REIMBURSEMENT_DUPLICATE_REJECTED = "REIMBURSEMENT_DUPLICATE_REJECTED";
    public static final String REIMBURSEMENT_NOT_ELIGIBLE_REJECTED = "REIMBURSEMENT_NOT_ELIGIBLE_REJECTED";
    public static final String REIMBURSEMENT_PAID = "REIMBURSEMENT_PAID";

    public static final String ANOMALY_DETECTED = "ANOMALY_DETECTED";
    public static final String COST_CENTER_CORRECTED = "COST_CENTER_CORRECTED";
    public static final String FINAL_APPROVAL_BLOCKED = "FINAL_APPROVAL_BLOCKED";

    private AuditEventType() {
    }
}
