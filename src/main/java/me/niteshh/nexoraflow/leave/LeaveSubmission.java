package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A leave submission exactly as received (API payload or generator input). References are plain strings
 * on purpose: an invalid reference must be preserved and reported, not lost.
 */
public record LeaveSubmission(
        String employeeId,
        String leavePolicyId,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal requestedDays,
        String reason,
        String supportingDocumentRef) {
}
