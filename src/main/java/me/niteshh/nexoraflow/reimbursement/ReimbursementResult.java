package me.niteshh.nexoraflow.reimbursement;

import me.niteshh.nexoraflow.common.ReasonCode;

/** Outcome of a reimbursement request, including rejected (duplicate / not eligible) attempts. */
public record ReimbursementResult(AttemptOutcome outcome, Reimbursement reimbursement, ReasonCode reasonCode, String message) {

    public boolean created() {
        return outcome == AttemptOutcome.CREATED;
    }
}
