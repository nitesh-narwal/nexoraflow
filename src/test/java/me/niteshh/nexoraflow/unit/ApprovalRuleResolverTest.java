package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.approval.ApprovalRule;
import me.niteshh.nexoraflow.approval.ApprovalRuleResolver;
import me.niteshh.nexoraflow.approval.ApproverRole;
import me.niteshh.nexoraflow.approval.RoutingContext;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

class ApprovalRuleResolverTest {

    private static ApprovalRule rule(String id, RequestType type, String min, String max, int level, ApproverRole role, int priority) {
        ApprovalRule r = new ApprovalRule();
        r.setApprovalRuleId(id);
        r.setWorkflowType(type);
        r.setMinimumAmount(new BigDecimal(min));
        r.setMaximumAmount(max == null ? null : new BigDecimal(max));
        r.setApprovalLevel(level);
        r.setApproverRole(role);
        r.setPriority(priority);
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        return r;
    }

    private static Employee requester(String country, String dept) {
        Employee e = new Employee();
        e.setEmployeeId("EMP-000010");
        e.setCountry(country);
        e.setDepartmentId(dept);
        return e;
    }

    private static RoutingContext expense(String amount, Set<ExpenseCategory> categories, Employee e) {
        return new RoutingContext(RequestType.EXPENSE, e, new BigDecimal(amount), categories, LocalDate.of(2026, 10, 22));
    }

    private final List<ApprovalRule> standard = List.of(
            rule("R1", RequestType.EXPENSE, "0", "500", 1, ApproverRole.DIRECT_MANAGER, 100),
            rule("R2", RequestType.EXPENSE, "500", "2000", 1, ApproverRole.MANAGER, 100),
            rule("R3", RequestType.EXPENSE, "2000", "5000", 1, ApproverRole.MANAGER, 100),
            rule("R4", RequestType.EXPENSE, "2000", "5000", 2, ApproverRole.FINANCE_REVIEWER, 100),
            rule("R5", RequestType.EXPENSE, "5000", null, 1, ApproverRole.MANAGER, 100),
            rule("R6", RequestType.EXPENSE, "5000", null, 2, ApproverRole.FINANCE_HEAD, 100));

    private List<String> ids(List<ApprovalRule> rules) {
        return rules.stream().map(ApprovalRule::getApprovalRuleId).toList();
    }

    @Test
    void amountBoundariesUseExclusiveLowerAndInclusiveUpperBound() {
        Employee e = requester("US", "DEP-ENG");
        assertThat(ids(ApprovalRuleResolver.select(standard, expense("500.00", Set.of(), e)))).containsExactly("R1");   // "up to 500"
        assertThat(ids(ApprovalRuleResolver.select(standard, expense("500.01", Set.of(), e)))).containsExactly("R2");
        assertThat(ids(ApprovalRuleResolver.select(standard, expense("2000.00", Set.of(), e)))).containsExactly("R2");
        assertThat(ids(ApprovalRuleResolver.select(standard, expense("2000.01", Set.of(), e)))).containsExactly("R3", "R4");
        assertThat(ids(ApprovalRuleResolver.select(standard, expense("5000.00", Set.of(), e)))).containsExactly("R3", "R4");
        assertThat(ids(ApprovalRuleResolver.select(standard, expense("5000.01", Set.of(), e)))).containsExactly("R5", "R6");
    }

    @Test
    void multiLevelRoutesAreOrderedByLevel() {
        List<ApprovalRule> selected = ApprovalRuleResolver.select(standard, expense("7000", Set.of(), requester("US", "DEP-ENG")));
        assertThat(selected).extracting(ApprovalRule::getApprovalLevel).containsExactly(1, 2);
        assertThat(selected).extracting(ApprovalRule::getApproverRole).containsExactly(ApproverRole.MANAGER, ApproverRole.FINANCE_HEAD);
    }

    @Test
    void inactiveAndExpiredRulesAreIgnored() {
        ApprovalRule inactive = rule("X1", RequestType.EXPENSE, "500", "2000", 2, ApproverRole.FINANCE_HEAD, 5);
        inactive.setRuleStatus(ApprovalRule.Status.INACTIVE);
        ApprovalRule expired = rule("X2", RequestType.EXPENSE, "0", "500", 1, ApproverRole.DEPARTMENT_HEAD, 5);
        expired.setEffectiveTo(LocalDate.of(2025, 12, 31));
        expired.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        ApprovalRule future = rule("X3", RequestType.EXPENSE, "0", "500", 1, ApproverRole.DEPARTMENT_HEAD, 5);
        future.setEffectiveFrom(LocalDate.of(2027, 1, 1));
        List<ApprovalRule> all = new java.util.ArrayList<>(standard);
        all.addAll(List.of(inactive, expired, future));
        assertThat(ids(ApprovalRuleResolver.select(all, expense("300", Set.of(), requester("US", "DEP-ENG"))))).containsExactly("R1");
        assertThat(ids(ApprovalRuleResolver.select(all, expense("1000", Set.of(), requester("US", "DEP-ENG"))))).containsExactly("R2");
    }

    @Test
    void lowerPriorityNumberWinsWithinALevelThenMoreSpecificRule() {
        ApprovalRule specific = rule("S1", RequestType.EXPENSE, "0", "500", 1, ApproverRole.DEPARTMENT_HEAD, 100);
        specific.setCountry("DE");
        ApprovalRule urgent = rule("S2", RequestType.EXPENSE, "0", "500", 1, ApproverRole.MANAGER, 10);
        List<ApprovalRule> rules = List.of(standard.get(0), specific, urgent);
        // priority 10 beats priority 100 regardless of specificity
        assertThat(ids(ApprovalRuleResolver.select(rules, expense("100", Set.of(), requester("DE", "DEP-ENG"))))).containsExactly("S2");
        // equal priority: the country-specific rule beats the generic one, but only for that country
        List<ApprovalRule> equal = List.of(standard.get(0), specific);
        assertThat(ids(ApprovalRuleResolver.select(equal, expense("100", Set.of(), requester("DE", "DEP-ENG"))))).containsExactly("S1");
        assertThat(ids(ApprovalRuleResolver.select(equal, expense("100", Set.of(), requester("US", "DEP-ENG"))))).containsExactly("R1");
    }

    @Test
    void categoryAndDepartmentFiltersApply() {
        ApprovalRule client = rule("C1", RequestType.EXPENSE, "0", "2000", 2, ApproverRole.FINANCE_REVIEWER, 10);
        client.setExpenseCategory(ExpenseCategory.CLIENT_ENTERTAINMENT);
        ApprovalRule sales = rule("D1", RequestType.EXPENSE, "0", "500", 3, ApproverRole.DEPARTMENT_HEAD, 10);
        sales.setDepartmentId("DEP-SAL");
        List<ApprovalRule> rules = List.of(standard.get(0), client, sales);
        assertThat(ids(ApprovalRuleResolver.select(rules, expense("100", Set.of(ExpenseCategory.MEALS), requester("US", "DEP-ENG"))))).containsExactly("R1");
        assertThat(ids(ApprovalRuleResolver.select(rules, expense("100", Set.of(ExpenseCategory.MEALS, ExpenseCategory.CLIENT_ENTERTAINMENT), requester("US", "DEP-ENG"))))).containsExactly("R1", "C1");
        assertThat(ids(ApprovalRuleResolver.select(rules, expense("100", Set.of(), requester("US", "DEP-SAL"))))).containsExactly("R1", "D1");
    }

    @Test
    void workflowTypesAreIsolatedAndLeaveUsesBusinessDays() {
        List<ApprovalRule> rules = new java.util.ArrayList<>(standard);
        rules.add(rule("L1", RequestType.LEAVE, "0", "10", 1, ApproverRole.DIRECT_MANAGER, 100));
        rules.add(rule("L2", RequestType.LEAVE, "10", null, 1, ApproverRole.DIRECT_MANAGER, 100));
        rules.add(rule("L3", RequestType.LEAVE, "10", null, 2, ApproverRole.DEPARTMENT_HEAD, 100));
        Employee e = requester("US", "DEP-ENG");
        assertThat(ids(ApprovalRuleResolver.select(rules, new RoutingContext(RequestType.LEAVE, e, new BigDecimal("3"), Set.of(), LocalDate.of(2026, 10, 22))))).containsExactly("L1");
        assertThat(ids(ApprovalRuleResolver.select(rules, new RoutingContext(RequestType.LEAVE, e, new BigDecimal("12"), Set.of(), LocalDate.of(2026, 10, 22))))).containsExactly("L2", "L3");
    }

    @Test
    void noMatchingRuleYieldsAnEmptyRoute() {
        assertThat(ApprovalRuleResolver.select(List.of(), expense("100", Set.of(), requester("US", "DEP-ENG")))).isEmpty();
    }
}
