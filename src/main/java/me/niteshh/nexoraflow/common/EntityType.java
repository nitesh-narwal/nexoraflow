package me.niteshh.nexoraflow.common;

/** Entity-type vocabulary used by audit events, anomalies and ground-truth labels. */
public final class EntityType {

    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String LEAVE_REQUEST = "LEAVE_REQUEST";
    public static final String EXPENSE_CLAIM = "EXPENSE_CLAIM";
    public static final String EXPENSE_LINE = "EXPENSE_LINE";
    public static final String APPROVAL_STEP = "APPROVAL_STEP";
    public static final String REIMBURSEMENT = "REIMBURSEMENT";
    public static final String LEAVE_BALANCE = "LEAVE_BALANCE";
    public static final String SUBMISSION = "SUBMISSION";
    public static final String COST_CENTER = "COST_CENTER";
    public static final String SYSTEM = "SYSTEM";

    private EntityType() {
    }

    public static String forRequest(RequestType type) {
        return type == RequestType.LEAVE ? LEAVE_REQUEST : EXPENSE_CLAIM;
    }
}
