package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.policy.EmployeePolicyAssignment;
import me.niteshh.nexoraflow.policy.LeavePolicy;

/**
 * Everything the leave engine needs, gathered by the workflow service. The engine itself performs no
 * I/O, which keeps every rule unit-testable and every decision reproducible.
 *
 * @param availableBalance   available balance of the employee for the policy and year, or null when there is no balance record
 * @param approvedOverlaps   OTHER approved requests of the same employee that intersect the requested range
 * @param holidays           public holidays of the employee's country covering the requested range
 */
public record LeaveEvaluationInput(
        String requestId,
        Employee employee,
        LeavePolicy policy,
        List<EmployeePolicyAssignment> assignments,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate submissionDate,
        String supportingDocumentRef,
        Set<LocalDate> holidays,
        BigDecimal availableBalance,
        List<LeaveRequest> approvedOverlaps) {
}
