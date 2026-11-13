package me.niteshh.nexoraflow.approval;

/** How an approver is located for a rule. Resolution logic lives in {@code ApproverResolver}. */
public enum ApproverRole {
    /** The requester's direct manager. */
    DIRECT_MANAGER,
    /** The nearest active manager (job level 3 or above) in the reporting line. */
    MANAGER,
    /** The nearest active department head (job level 2 or above) in the reporting line. */
    DEPARTMENT_HEAD,
    /** A Finance Manager from the Finance department. */
    FINANCE_REVIEWER,
    /** The Finance head (VP Finance). */
    FINANCE_HEAD
}
