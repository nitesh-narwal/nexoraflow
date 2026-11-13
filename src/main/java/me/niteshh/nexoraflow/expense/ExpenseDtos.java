package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReviewAction;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

public final class ExpenseDtos {

    private ExpenseDtos() {
    }

    public record ReceiptUploadRequest(
            @NotBlank @Size(max = 200) String fileName,
            @NotBlank @Size(max = 20) String fileType,
            @Positive long fileSizeBytes,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a lower-case hex SHA-256") String receiptHash,
            @Size(max = 120) String sourceReference) {
    }

    public record ExpenseLineRequest(
            @NotBlank String category,
            @NotBlank @Size(max = 160) String merchantName,
            @NotNull LocalDate transactionDate,
            @Size(max = 300) String description,
            @NotNull BigDecimal amount,
            @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 style code") String currency,
            @Size(max = 40) String receiptId,
            @Valid ReceiptUploadRequest receipt) {
    }

    public record SubmitExpenseRequest(
            @NotBlank String employeeId,
            @NotNull LocalDate expensePeriodStart,
            @NotNull LocalDate expensePeriodEnd,
            @Size(max = 80) String costCenterId,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "must be an ISO-4217 style code") String currency,
            @NotEmpty @Valid List<ExpenseLineRequest> lines) {

        public ExpenseSubmission toSubmission() {
            return new ExpenseSubmission(employeeId, expensePeriodStart, expensePeriodEnd, costCenterId, currency,
                    lines.stream().map(l -> new ExpenseSubmission.Line(l.category(), l.merchantName(), l.transactionDate(),
                            l.description(), l.amount(), l.currency(), l.receiptId(),
                            l.receipt() == null ? null : new ExpenseSubmission.ReceiptUpload(l.receipt().fileName(),
                                    l.receipt().fileType(), l.receipt().fileSizeBytes(), l.receipt().receiptHash(),
                                    l.receipt().sourceReference()))).toList());
        }
    }

    public record ActorRequest(@NotBlank String actorEmployeeId, @Size(max = 500) String reason) {
    }

    public record ReviewRequest(@NotBlank String actorEmployeeId, @NotNull ReviewAction action, @Size(max = 500) String comment) {
    }

    public record CostCenterCorrectionRequest(@NotBlank String actorEmployeeId, @NotBlank String costCenterId) {
    }

    public record ExpenseLineResponse(String expenseLineId, int lineNumber, String expensePolicyId, ExpenseCategory expenseCategory,
                                      String merchantName, LocalDate transactionDate, String description, BigDecimal amount,
                                      String currency, BigDecimal amountReporting, String receiptId, String submittedReceiptRef,
                                      String costCenterId, LineStatus lineStatus, String validationReason) {
        public static ExpenseLineResponse from(ExpenseLine l) {
            return new ExpenseLineResponse(l.getExpenseLineId(), l.getLineNumber(), l.getExpensePolicyId(), l.getExpenseCategory(),
                    l.getMerchantName(), l.getTransactionDate(), l.getDescription(), l.getAmount(), l.getCurrency(),
                    l.getAmountReporting(), l.getReceiptId(), l.getSubmittedReceiptRef(), l.getCostCenterId(),
                    l.getLineStatus(), l.getValidationReason());
        }
    }

    public record ExpenseClaimResponse(String expenseClaimId, String employeeId, LocalDate claimDate,
                                       LocalDate expensePeriodStart, LocalDate expensePeriodEnd, LocalDate submissionDate,
                                       String costCenterId, String submittedCostCenterRef, String currency,
                                       BigDecimal totalAmount, ExpenseClaimStatus claimStatus,
                                       EligibilityStatus eligibilityStatus, Decision decision, String decisionReason,
                                       Instant createdAt, Instant updatedAt, List<ExpenseLineResponse> lines) {
        public static ExpenseClaimResponse from(ExpenseClaim c) {
            return new ExpenseClaimResponse(c.getExpenseClaimId(), c.getEmployeeId(), c.getClaimDate(), c.getExpensePeriodStart(),
                    c.getExpensePeriodEnd(), c.getSubmissionDate(), c.getCostCenterId(), c.getSubmittedCostCenterRef(),
                    c.getCurrency(), c.getTotalAmount(), c.getClaimStatus(), c.getEligibilityStatus(), c.getDecision(),
                    c.getDecisionReason(), c.getCreatedAt(), c.getUpdatedAt(),
                    c.getLines().stream().map(ExpenseLineResponse::from).toList());
        }
    }
}
