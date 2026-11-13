package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReviewAction;

public final class LeaveDtos {

    private LeaveDtos() {
    }

    public record SubmitLeaveRequest(
            @NotBlank String employeeId,
            @NotBlank String leavePolicyId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @PositiveOrZero BigDecimal requestedDays,
            @Size(max = 500) String reason,
            @Size(max = 200) String supportingDocumentRef) {

        public LeaveSubmission toSubmission() {
            return new LeaveSubmission(employeeId, leavePolicyId, startDate, endDate, requestedDays, reason, supportingDocumentRef);
        }
    }

    public record ActorRequest(@NotBlank String actorEmployeeId, @Size(max = 500) String reason) {
    }

    public record ReviewRequest(@NotBlank String actorEmployeeId, @NotNull ReviewAction action, @Size(max = 500) String comment) {
    }

    public record LeaveRequestResponse(String leaveRequestId, String employeeId, String leavePolicyId, LocalDate startDate,
                                       LocalDate endDate, BigDecimal requestedDays, BigDecimal calculatedBusinessDays,
                                       String reason, LocalDate submissionDate, LeaveRequestStatus requestStatus,
                                       Decision decision, String decisionReason, String supportingDocumentRef,
                                       Instant createdAt, Instant updatedAt) {
        public static LeaveRequestResponse from(LeaveRequest r) {
            return new LeaveRequestResponse(r.getLeaveRequestId(), r.getEmployeeId(), r.getLeavePolicyId(), r.getStartDate(),
                    r.getEndDate(), r.getRequestedDays(), r.getCalculatedBusinessDays(), r.getReason(), r.getSubmissionDate(),
                    r.getRequestStatus(), r.getDecision(), r.getDecisionReason(), r.getSupportingDocumentRef(),
                    r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record LeaveBalanceResponse(String leaveBalanceId, String employeeId, String leavePolicyId, int year,
                                       BigDecimal openingBalance, BigDecimal accruedBalance, BigDecimal carriedForwardBalance,
                                       BigDecimal usedBalance, BigDecimal pendingBalance, BigDecimal availableBalance,
                                       Instant lastUpdatedAt) {
        public static LeaveBalanceResponse from(LeaveBalance b) {
            return new LeaveBalanceResponse(b.getLeaveBalanceId(), b.getEmployeeId(), b.getLeavePolicyId(), b.getBalanceYear(),
                    b.getOpeningBalance(), b.getAccruedBalance(), b.getCarriedForwardBalance(), b.getUsedBalance(),
                    b.getPendingBalance(), b.computeAvailable(), b.getLastUpdatedAt());
        }
    }
}
