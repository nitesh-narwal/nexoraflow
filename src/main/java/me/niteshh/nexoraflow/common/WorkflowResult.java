package me.niteshh.nexoraflow.common;

/**
 * Result of a workflow command. Business-rule violations are returned (not thrown) so that the
 * transaction commits and the audit event describing the rejected attempt is preserved. Controllers
 * translate a rejected result into the appropriate HTTP error after the transaction has completed.
 */
public record WorkflowResult<T>(boolean accepted, T value, ReasonCode reasonCode, String message) {

    public static <T> WorkflowResult<T> accepted(T value) {
        return new WorkflowResult<>(true, value, null, null);
    }

    public static <T> WorkflowResult<T> rejected(ReasonCode code, String message) {
        return new WorkflowResult<>(false, null, code, message);
    }

    public static <T> WorkflowResult<T> rejected(T value, ReasonCode code, String message) {
        return new WorkflowResult<>(false, value, code, message);
    }

    public T orThrow() {
        if (accepted) {
            return value;
        }
        throw switch (reasonCode) {
            case SELF_APPROVAL_NOT_ALLOWED, UNAUTHORIZED_APPROVER, APPROVER_INACTIVE ->
                    me.niteshh.nexoraflow.common.exception.NexoraException.forbidden(reasonCode, message);
            case INVALID_STATE_TRANSITION, INVALID_STEP_ORDER, STEP_ALREADY_COMPLETED, DUPLICATE_REIMBURSEMENT ->
                    me.niteshh.nexoraflow.common.exception.NexoraException.conflict(reasonCode, message);
            default -> me.niteshh.nexoraflow.common.exception.NexoraException.unprocessable(reasonCode, message);
        };
    }
}
