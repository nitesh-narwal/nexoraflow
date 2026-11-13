package me.niteshh.nexoraflow.approval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

/**
 * Inputs that determine approval authority.
 *
 * @param amount USD-equivalent total for EXPENSE, calculated business days for LEAVE
 * @param categories every expense category present on the claim (empty for leave)
 */
public record RoutingContext(
        RequestType requestType,
        Employee requester,
        BigDecimal amount,
        Set<ExpenseCategory> categories,
        LocalDate effectiveDate) {
}
