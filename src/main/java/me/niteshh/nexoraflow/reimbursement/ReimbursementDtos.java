package me.niteshh.nexoraflow.reimbursement;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.NotBlank;

public final class ReimbursementDtos {

    private ReimbursementDtos() {
    }

    public record CreateReimbursementRequest(@NotBlank String expenseClaimId, @NotBlank String requestedBy) {
    }

    public record ProcessRequest(@NotBlank String actor) {
    }

    public record ReimbursementResponse(String reimbursementId, String expenseClaimId, String employeeId,
                                        BigDecimal approvedAmount, String currency, ReimbursementStatus reimbursementStatus,
                                        Instant approvedAt, Instant processedAt, String paymentReference, Instant createdAt) {
        public static ReimbursementResponse from(Reimbursement r) {
            return new ReimbursementResponse(r.getReimbursementId(), r.getExpenseClaimId(), r.getEmployeeId(),
                    r.getApprovedAmount(), r.getCurrency(), r.getReimbursementStatus(), r.getApprovedAt(),
                    r.getProcessedAt(), r.getPaymentReference(), r.getCreatedAt());
        }
    }
}
