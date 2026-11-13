package me.niteshh.nexoraflow.generator.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.budget.BudgetRepository;
import me.niteshh.nexoraflow.configuration.CurrencyRepository;
import me.niteshh.nexoraflow.costcenter.CostCenter;
import me.niteshh.nexoraflow.costcenter.CostCenterRepository;
import me.niteshh.nexoraflow.employee.EmployeeCostCenterRepository;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.generator.GeneratorSeeds;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.master.ReservedPool;
import me.niteshh.nexoraflow.policy.ExpensePolicyRepository;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicyRepository;

/** Loads the master data a planner needs. Reads ordinary business tables only. */
@Component
public class PlanningContextFactory {

    private final EmployeeRepository employees;
    private final LeavePolicyRepository leavePolicies;
    private final ExpensePolicyRepository expensePolicies;
    private final EmployeeCostCenterRepository assignments;
    private final BudgetRepository budgets;
    private final CostCenterRepository costCenters;
    private final CurrencyRepository currencies;
    private final JdbcTemplate jdbc;

    public PlanningContextFactory(EmployeeRepository employees, LeavePolicyRepository leavePolicies,
                                  ExpensePolicyRepository expensePolicies, EmployeeCostCenterRepository assignments,
                                  BudgetRepository budgets, CostCenterRepository costCenters, CurrencyRepository currencies,
                                  JdbcTemplate jdbc) {
        this.employees = employees;
        this.leavePolicies = leavePolicies;
        this.expensePolicies = expensePolicies;
        this.assignments = assignments;
        this.budgets = budgets;
        this.costCenters = costCenters;
        this.currencies = currencies;
        this.jdbc = jdbc;
    }

    public PlanningContext load(GeneratorSettings settings, GeneratorSeeds seeds) {
        OrgIndex org = new OrgIndex(employees.findAllOrdered());
        ReservedPool pool = new ReservedPool(org.all());

        Map<String, Set<LocalDate>> holidays = new HashMap<>();
        jdbc.query("""
                SELECT c.country, h.holiday_date FROM holiday h JOIN holiday_calendar c ON c.calendar_id = h.calendar_id
                """, rs -> {
            holidays.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getDate(2).toLocalDate());
        });

        Map<String, LeavePolicy> policies = new HashMap<>();
        leavePolicies.findAll().forEach(p -> policies.put(p.getLeavePolicyId(), p));

        Map<String, PlanningContext.BalanceSeed> balances = new HashMap<>();
        jdbc.query("SELECT employee_id, leave_policy_id, opening_balance, carried_forward_balance FROM leave_balance WHERE balance_year = ?",
                rs -> {
                    balances.put(PlanningContext.balanceKey(rs.getString(1), rs.getString(2)),
                            new PlanningContext.BalanceSeed(rs.getBigDecimal(3), rs.getBigDecimal(4)));
                }, settings.year());

        Map<String, CostCenter> centers = new HashMap<>();
        costCenters.findAll().forEach(c -> centers.put(c.getCostCenterId(), c));
        Map<String, BigDecimal> rates = new HashMap<>();
        currencies.findAll().forEach(c -> rates.put(c.getCurrencyCode(), c.getRateToReporting()));

        return new PlanningContext(settings, seeds, org, pool, holidays, policies, expensePolicies.findAllByOrderByExpensePolicyId(),
                balances,
                assignments.findAll().stream().sorted(java.util.Comparator.comparing(me.niteshh.nexoraflow.employee.EmployeeCostCenter::getAssignmentId)).toList(),
                budgets.findAll().stream().sorted(java.util.Comparator.comparing(me.niteshh.nexoraflow.budget.Budget::getBudgetId)).toList(), centers, rates);
    }
}
