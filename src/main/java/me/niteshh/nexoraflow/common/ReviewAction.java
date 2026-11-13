package me.niteshh.nexoraflow.common;

/** Resolution an operations analyst can apply to an exception (NEEDS_REVIEW / FLAGGED / ON_HOLD). */
public enum ReviewAction {
    /** Waive the soft exception and send the request into the approval workflow. */
    RELEASE,
    /** Reject the request. */
    REJECT
}
