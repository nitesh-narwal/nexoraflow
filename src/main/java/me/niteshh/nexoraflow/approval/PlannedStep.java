package me.niteshh.nexoraflow.approval;

/**
 * One step of a planned approval route.
 *
 * @param skipped                 true when the approver already appears at an earlier level (no second approval needed)
 * @param escalatedFromEmployeeId set when the natural approver was unavailable and the step moved up the reporting line
 */
public record PlannedStep(
        int level,
        ApproverRole role,
        String approverEmployeeId,
        String approvalRuleId,
        boolean skipped,
        boolean escalated,
        String escalatedFromEmployeeId,
        String note) {
}
