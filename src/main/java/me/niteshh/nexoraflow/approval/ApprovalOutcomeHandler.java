package me.niteshh.nexoraflow.approval;

import java.util.Optional;

import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;

/**
 * Implemented by each request module (leave, expense). The approval engine knows nothing about leave
 * balances or reimbursements: it delegates the consequences of the final decision to the owning module.
 */
public interface ApprovalOutcomeHandler {

    RequestType requestType();

    /** Employee ID of the requester, used for the self-approval rule. */
    Optional<String> requesterOf(String requestId);

    /** True when the request is in PENDING_APPROVAL and can therefore receive approval actions. */
    boolean awaitingApproval(String requestId);

    /** All required steps are approved: finalise the request (consume balance, create reimbursement...). */
    FinalApprovalOutcome onFinalApproval(String requestId, String actor);

    /** A step was rejected: the request is rejected and must not continue through approval. */
    void onRejected(String requestId, String actor, String reason);

    record FinalApprovalOutcome(boolean approved, ReasonCode blockedReason, String message) {
        public static FinalApprovalOutcome ok() {
            return new FinalApprovalOutcome(true, null, null);
        }

        public static FinalApprovalOutcome blocked(ReasonCode code, String message) {
            return new FinalApprovalOutcome(false, code, message);
        }
    }
}
