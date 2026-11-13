package me.niteshh.nexoraflow.common.rules;

/**
 * What a failed rule does to the request. The precedence used by {@link DecisionAggregator} is
 * HARD (reject) &gt; HOLD &gt; FLAG &gt; REVIEW.
 */
public enum RuleSeverity {
    /** The request is invalid and is rejected. */
    HARD,
    /** The request cannot proceed until a blocking condition (budget, cost center) is resolved. */
    HOLD,
    /** The request looks suspicious and is flagged for the operations analyst. */
    FLAG,
    /** The request is plausible but needs a human check (e.g. missing documentation). */
    REVIEW
}
