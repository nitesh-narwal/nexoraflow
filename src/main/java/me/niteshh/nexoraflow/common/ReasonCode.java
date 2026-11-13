package me.niteshh.nexoraflow.common;

/**
 * Explicit, stable reason codes for every business decision. Codes are part of the public contract:
 * they are stored on requests, written to audit events, exported, and used by the evaluation module.
 */
public enum ReasonCode {

    // ---- shared between leave and expense
    INACTIVE_EMPLOYEE("Employee is not active (inactive or terminated) for the relevant period"),
    INVALID_DATE_RANGE("Dates are missing, inverted or outside the permitted window"),
    POLICY_LIMIT_EXCEEDED("A configured policy limit is exceeded"),
    POLICY_NOT_EFFECTIVE("A policy exists but is not effective on the relevant date"),
    NO_VALID_APPROVER("No active, authorised approver could be determined for the approval path"),
    ESCALATED_UNAVAILABLE_APPROVER("Approver unavailable; approval escalated up the reporting line"),

    // ---- leave
    INSUFFICIENT_BALANCE("Available leave balance is lower than the requested business days"),
    OVERLAPPING_APPROVED_LEAVE("Requested dates overlap leave that is already approved"),
    INSUFFICIENT_NOTICE("Minimum notice period for this leave policy is not satisfied"),
    HOLIDAY_OR_NON_WORKING_DAY_ONLY("Requested range contains no working day (weekends / public holidays only)"),
    EMPLOYEE_NOT_ELIGIBLE("Employee has no effective assignment to the requested leave policy"),
    DOCUMENTATION_MISSING("Supporting documentation is required but was not provided"),

    // ---- expense
    RECEIPT_MISSING("A receipt is required for this amount but none was provided"),
    RECEIPT_INVALID("Receipt is of an unsupported file type or has an invalid status"),
    INVALID_RECEIPT_REFERENCE("Submitted receipt reference does not exist"),
    DUPLICATE_RECEIPT("The same receipt (hash) is already attached to another claim line"),
    INSUFFICIENT_BUDGET("Cost-center budget for the period cannot cover this claim"),
    INVALID_COST_CENTER("Cost center is missing, unknown or not active"),
    UNAUTHORIZED_COST_CENTER("Employee is not assigned to / authorised for this cost center"),
    OUTSIDE_EMPLOYMENT_PERIOD("Transaction date is before joining or after termination"),
    SUSPICIOUS_SPLIT_CLAIM("Related claims individually stay under an approval threshold but exceed it combined"),
    DUPLICATE_SUBMISSION("An identical expense line was already submitted"),
    INVALID_CURRENCY("Currency is unsupported or inconsistent with the claim"),
    INVALID_AMOUNT("Amount is missing, zero or negative"),
    NO_APPLICABLE_POLICY("No expense policy exists for this category, country and employee type"),
    UNSUPPORTED_CATEGORY("Expense category is not supported"),
    NO_LINE_ITEMS("A claim must contain at least one line item"),
    TOO_MANY_LINE_ITEMS("A claim may contain at most the configured number of line items"),

    // ---- decisions taken by people (not rule violations)
    REJECTED_BY_APPROVER("Rejected by an approver"),
    CANCELLED_BY_REQUESTER("Cancelled by the requester"),
    RELEASED_BY_ANALYST("Exception reviewed and released by an operations analyst"),
    REJECTED_BY_ANALYST("Exception reviewed and rejected by an operations analyst"),

    // ---- approval workflow
    UNAUTHORIZED_APPROVER("Actor is not the assigned or authorised approver for this step"),
    SELF_APPROVAL_NOT_ALLOWED("Requesters cannot approve their own requests"),
    APPROVER_INACTIVE("Approver is not an active employee"),
    INVALID_STEP_ORDER("Approval steps must be completed in the configured order"),
    STEP_ALREADY_COMPLETED("Completed approval steps cannot be overwritten"),
    INVALID_STATE_TRANSITION("The requested lifecycle transition is not allowed from the current state"),

    // ---- reimbursement
    DUPLICATE_REIMBURSEMENT("A reimbursement already exists for this claim"),
    CLAIM_NOT_REIMBURSABLE("Claim is not eligible and finally approved"),

    // ---- intake
    EMPLOYEE_NOT_FOUND("Submitted employee reference does not exist"),
    INVALID_REFERENCE("A submitted reference could not be resolved");

    private final String description;

    ReasonCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
