package me.niteshh.nexoraflow.approval;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import me.niteshh.nexoraflow.common.RequestType;

public final class ApprovalDtos {

    private ApprovalDtos() {
    }

    public record ApprovalStepResponse(String approvalStepId, RequestType requestType, String requestId, int stepSequence,
                                       int approvalLevel, ApproverRole approverRole, String approverEmployeeId,
                                       ApprovalStatus approvalStatus, String approvalRuleId, String escalatedFromStepId,
                                       Instant assignedAt, Instant actionAt, String comments, String decisionReason) {
        public static ApprovalStepResponse from(ApprovalStep s) {
            return new ApprovalStepResponse(s.getApprovalStepId(), s.getRequestType(), s.targetRequestId(), s.getStepSequence(),
                    s.getApprovalLevel(), s.getApproverRole(), s.getApproverEmployeeId(), s.getApprovalStatus(),
                    s.getApprovalRuleId(), s.getEscalatedFromStepId(), s.getAssignedAt(), s.getActionAt(), s.getComments(),
                    s.getDecisionReason());
        }
    }

    public record ActionRequest(@NotBlank String actorEmployeeId, @Size(max = 500) String comments, @Size(max = 300) String reason) {
        public ApprovalService.ActionCommand toCommand() {
            return new ApprovalService.ActionCommand(actorEmployeeId, comments, reason);
        }
    }

    public record ApprovalRuleResponse(String approvalRuleId, RequestType workflowType, String country, String departmentId,
                                       String expenseCategory, java.math.BigDecimal minimumAmount,
                                       java.math.BigDecimal maximumAmount, String amountUnit, int approvalLevel,
                                       ApproverRole approverRole, String approverDesignation, java.time.LocalDate effectiveFrom,
                                       java.time.LocalDate effectiveTo, int priority, String ruleStatus, String description) {
        public static ApprovalRuleResponse from(ApprovalRule r) {
            return new ApprovalRuleResponse(r.getApprovalRuleId(), r.getWorkflowType(), r.getCountry(), r.getDepartmentId(),
                    r.getExpenseCategory() == null ? null : r.getExpenseCategory().name(), r.getMinimumAmount(),
                    r.getMaximumAmount(), r.getAmountUnit(), r.getApprovalLevel(), r.getApproverRole(),
                    r.getApproverDesignation(), r.getEffectiveFrom(), r.getEffectiveTo(), r.getPriority(),
                    r.getRuleStatus().name(), r.getDescription());
        }
    }
}
