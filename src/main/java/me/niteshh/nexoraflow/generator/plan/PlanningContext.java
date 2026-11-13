package me.niteshh.nexoraflow.generator.plan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.niteshh.nexoraflow.budget.Budget;
import me.niteshh.nexoraflow.costcenter.CostCenter;
import me.niteshh.nexoraflow.employee.EmployeeCostCenter;
import me.niteshh.nexoraflow.generator.GeneratorSeeds;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.master.ReservedPool;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicy;

/**
 * Everything a planner may read: the persisted master data (ordinary business records) and the settings.
 * Ground-truth tables and engine results are never part of the context.
 */
public final class PlanningContext {

    public record BalanceSeed(BigDecimal opening, BigDecimal carriedForward) {
    }

    public final GeneratorSettings settings;
    public final GeneratorSeeds seeds;
    public final OrgIndex org;
    public final ReservedPool pool;
    public final ApprovalOracle oracle;
    public final Map<String, Set<LocalDate>> holidays;
    public final Map<String, LeavePolicy> leavePolicies;
    public final List<ExpensePolicy> expensePolicies;
    public final Map<String, BalanceSeed> balanceSeeds;
    public final List<EmployeeCostCenter> assignments;
    public final List<Budget> budgets;
    public final Map<String, CostCenter> costCenters;
    public final Map<String, BigDecimal> rateToUsd;

    private int leaveKeys;
    private int claimKeys;

    public PlanningContext(GeneratorSettings settings, GeneratorSeeds seeds, OrgIndex org, ReservedPool pool,
                           Map<String, Set<LocalDate>> holidays, Map<String, LeavePolicy> leavePolicies,
                           List<ExpensePolicy> expensePolicies, Map<String, BalanceSeed> balanceSeeds,
                           List<EmployeeCostCenter> assignments, List<Budget> budgets, Map<String, CostCenter> costCenters,
                           Map<String, BigDecimal> rateToUsd) {
        this.settings = settings;
        this.seeds = seeds;
        this.org = org;
        this.pool = pool;
        this.oracle = new ApprovalOracle(org);
        this.holidays = holidays;
        this.leavePolicies = leavePolicies;
        this.expensePolicies = expensePolicies;
        this.balanceSeeds = balanceSeeds;
        this.assignments = assignments;
        this.budgets = budgets;
        this.costCenters = costCenters;
        this.rateToUsd = rateToUsd;
    }

    public String nextLeaveKey() {
        return "LV-" + String.format("%06d", ++leaveKeys);
    }

    public String nextClaimKey() {
        return "CL-" + String.format("%06d", ++claimKeys);
    }

    public static String balanceKey(String employeeId, String policyId) {
        return employeeId + "|" + policyId;
    }
}
