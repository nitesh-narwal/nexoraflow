package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.EmployeePolicyAssignment;
import me.niteshh.nexoraflow.policy.EmployeePolicyAssignmentRepository;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicyRepository;

/**
 * Gives an employee the leave policies they are eligible for (policy assignments) and opens their
 * balances for a year. Used by both the employee API and the synthetic-data generator, so generated
 * data follows exactly the same provisioning rules as production data.
 *
 * <p>Pro-rating: an UPFRONT entitlement for someone joining mid-year is scaled by the number of months
 * from the joining month to December, inclusive. MONTHLY policies grant nothing at provisioning; the
 * accrual job posts one instalment per month.
 */
@Service
public class LeaveProvisioningService {

    private static final String SYSTEM = "SYSTEM";

    private final LeavePolicyRepository policies;
    private final EmployeePolicyAssignmentRepository assignments;
    private final LeaveBalanceService balances;
    private final IdService ids;

    public LeaveProvisioningService(LeavePolicyRepository policies, EmployeePolicyAssignmentRepository assignments,
                                    LeaveBalanceService balances, IdService ids) {
        this.policies = policies;
        this.assignments = assignments;
        this.balances = balances;
        this.ids = ids;
    }

    @Transactional
    public List<LeaveBalance> provision(Employee employee, int year, Function<LeavePolicy, BigDecimal> carryForward) {
        List<LeaveBalance> opened = new ArrayList<>();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        for (LeavePolicy policy : policies.findAllByOrderByLeavePolicyId()) {
            if (!policy.isActive() || !policy.eligibleTypes().contains(employee.getEmploymentType())) {
                continue;
            }
            if (policy.getCountry() != null && !policy.getCountry().equals(employee.getCountry())) {
                continue;
            }
            if (policy.getEffectiveFrom().isAfter(yearEnd) || (policy.getEffectiveTo() != null && policy.getEffectiveTo().isBefore(yearStart))) {
                continue;
            }
            EmployeePolicyAssignment assignment = new EmployeePolicyAssignment();
            assignment.setAssignmentId(ids.policyAssignmentId());
            assignment.setEmployeeId(employee.getEmployeeId());
            assignment.setLeavePolicyId(policy.getLeavePolicyId());
            LocalDate from = employee.getJoiningDate().isAfter(policy.getEffectiveFrom()) ? employee.getJoiningDate() : policy.getEffectiveFrom();
            assignment.setEffectiveFrom(from);
            assignment.setEffectiveTo(employee.getTerminationDate());
            assignments.save(assignment);

            LeaveBalance balance = balances.createEmpty(employee.getEmployeeId(), policy.getLeavePolicyId(), year);
            LocalDate grantDate = employee.getJoiningDate().isAfter(yearStart) ? employee.getJoiningDate() : yearStart;
            if (policy.getAccrualFrequency() == AccrualFrequency.ANNUAL_UPFRONT) {
                BigDecimal grant = prorate(policy.getAnnualEntitlement(), employee.getJoiningDate(), year);
                if (grant.signum() > 0) {
                    balances.post(balance, BalanceTransactionType.OPENING_GRANT, null, null, grant, Money.ZERO, Money.ZERO,
                            Money.ZERO, Money.ZERO, grantDate, SYSTEM, "Opening grant " + year + " (" + policy.getLeavePolicyId() + ")");
                }
            }
            BigDecimal carry = carryForward == null ? Money.ZERO : Money.scale(carryForward.apply(policy));
            carry = carry.min(policy.getCarryForwardLimit());
            if (carry.signum() > 0 && employee.getJoiningDate().isBefore(yearStart)) {
                balances.post(balance, BalanceTransactionType.CARRY_FORWARD, null, null, Money.ZERO, Money.ZERO, carry,
                        Money.ZERO, Money.ZERO, yearStart, SYSTEM, "Carry forward from " + (year - 1));
            }
            opened.add(balance);
        }
        return opened;
    }

    /** Entitlement scaled by months employed in the year (full year for staff who joined earlier). */
    public static BigDecimal prorate(BigDecimal entitlement, LocalDate joiningDate, int year) {
        if (joiningDate.getYear() > year) {
            return Money.ZERO;
        }
        int months = joiningDate.getYear() < year ? 12 : 12 - (joiningDate.getMonthValue() - 1);
        return entitlement.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12), 2, Money.ROUNDING);
    }
}
