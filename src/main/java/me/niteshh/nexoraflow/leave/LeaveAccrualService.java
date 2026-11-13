package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicyRepository;

/**
 * Posts monthly accruals for MONTHLY policies. Idempotent per balance and month (a unique ledger index
 * guards it). The cumulative-rounding formula guarantees the twelve instalments sum to exactly the annual
 * entitlement.
 */
@Service
public class LeaveAccrualService {

    private final LeaveBalanceRepository balances;
    private final LeaveBalanceTransactionRepository transactions;
    private final LeaveBalanceService balanceService;
    private final LeavePolicyRepository policies;
    private final EmployeeRepository employees;

    public LeaveAccrualService(LeaveBalanceRepository balances, LeaveBalanceTransactionRepository transactions,
                               LeaveBalanceService balanceService, LeavePolicyRepository policies,
                               EmployeeRepository employees) {
        this.balances = balances;
        this.transactions = transactions;
        this.balanceService = balanceService;
        this.policies = policies;
        this.employees = employees;
    }

    /** Accrues the given month for every eligible monthly balance. Returns the number of postings. */
    @Transactional
    public int accrueMonth(YearMonth period, String actor) {
        int posted = 0;
        LocalDate monthStart = period.atDay(1);
        String periodKey = period.toString();
        for (LeaveBalance balance : balances.findByBalanceYearOrderByLeaveBalanceId(period.getYear())) {
            LeavePolicy policy = policies.findById(balance.getLeavePolicyId()).orElse(null);
            Employee employee = employees.findById(balance.getEmployeeId()).orElse(null);
            if (policy == null || employee == null || policy.getAccrualFrequency() != AccrualFrequency.MONTHLY) {
                continue;
            }
            if (employee.getTerminationDate() != null && employee.getTerminationDate().isBefore(monthStart)) {
                continue;
            }
            YearMonth first = firstAccrualMonth(employee.getJoiningDate(), period.getYear());
            if (period.isBefore(first)) {
                continue;
            }
            if (transactions.existsByLeaveBalanceIdAndPeriodKeyAndTxnType(balance.getLeaveBalanceId(), periodKey,
                    BalanceTransactionType.ACCRUAL)) {
                continue;
            }
            int k = (int) ChronoUnit.MONTHS.between(first, period) + 1;
            BigDecimal amount = cumulative(policy.getAnnualEntitlement(), k)
                    .subtract(cumulative(policy.getAnnualEntitlement(), k - 1));
            if (amount.signum() <= 0) {
                continue;
            }
            balanceService.post(balance, BalanceTransactionType.ACCRUAL, null, periodKey,
                    Money.ZERO, amount, Money.ZERO, Money.ZERO, Money.ZERO, monthStart, actor,
                    "Monthly accrual " + periodKey);
            posted++;
        }
        return posted;
    }

    /** First month in which an employee accrues: January for existing staff, the next month for joiners mid-month. */
    static YearMonth firstAccrualMonth(LocalDate joiningDate, int year) {
        if (joiningDate.getYear() < year) {
            return YearMonth.of(year, 1);
        }
        YearMonth joined = YearMonth.from(joiningDate);
        return joiningDate.getDayOfMonth() == 1 ? joined : joined.plusMonths(1);
    }

    /** round2(entitlement * k / 12), the cumulative accrual after k monthly instalments (k capped at 12). */
    static BigDecimal cumulative(BigDecimal entitlement, int k) {
        int capped = Math.max(0, Math.min(12, k));
        return entitlement.multiply(BigDecimal.valueOf(capped)).divide(BigDecimal.valueOf(12), 2, Money.ROUNDING);
    }
}
