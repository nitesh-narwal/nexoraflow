package me.niteshh.nexoraflow.generator.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import me.niteshh.nexoraflow.approval.ApprovalRule;
import me.niteshh.nexoraflow.approval.ApproverRole;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeaveType;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;
import me.niteshh.nexoraflow.policy.PolicyStatus;

/** Builds the seed rows of the configurable policy tables from the documented business-policy baseline. */
public final class PolicyCatalog {

    public static final List<String> COUNTRIES = List.of("IN", "US", "GB", "DE", "SG");
    private static final LocalDate POLICY_START = LocalDate.of(2025, 1, 1);

    private PolicyCatalog() {
    }

    // ------------------------------------------------------------------ leave (6 policies)

    public static List<LeavePolicy> leavePolicies() {
        List<LeavePolicy> list = new ArrayList<>();
        list.add(annual("IN", "Annual Leave (India)", "18", AccrualFrequency.MONTHLY, 15, 3));
        list.add(annual("US", "Paid Time Off (United States)", "15", AccrualFrequency.ANNUAL_UPFRONT, 15, 5));
        list.add(annual("GB", "Annual Leave (United Kingdom)", "25", AccrualFrequency.ANNUAL_UPFRONT, 20, 7));
        list.add(annual("DE", "Annual Leave (Germany)", "30", AccrualFrequency.ANNUAL_UPFRONT, 20, 7));
        LeavePolicy sg = annual("SG", "Annual Leave (Singapore)", "18", AccrualFrequency.ANNUAL_UPFRONT, 15, 5);
        sg.setEffectiveTo(LocalDate.of(2026, 12, 31)); // valid through the dataset year; used by the effective-date scenario
        list.add(sg);

        LeavePolicy sick = base("LPOL-SICK", "Sick Leave (global)", LeaveType.SICK, null, "10", AccrualFrequency.ANNUAL_UPFRONT);
        sick.setCarryForwardLimit(BigDecimal.ZERO);
        sick.setMaximumConsecutiveDays(10);
        sick.setMinimumNoticeDays(0);
        sick.setRequiresDocumentation(true);
        sick.setDocumentationThresholdDays(2);
        sick.setEligibleEmploymentTypes("FULL_TIME,PART_TIME,INTERN");
        list.add(sick);
        return list;
    }

    private static LeavePolicy annual(String country, String name, String days, AccrualFrequency accrual, int maxConsecutive, int notice) {
        LeavePolicy p = base("LPOL-ANNUAL-" + country, name, LeaveType.ANNUAL, country, days, accrual);
        p.setCarryForwardLimit(new BigDecimal("5"));
        p.setMaximumConsecutiveDays(maxConsecutive);
        p.setMinimumNoticeDays(notice);
        p.setEligibleEmploymentTypes("FULL_TIME,PART_TIME");
        return p;
    }

    private static LeavePolicy base(String id, String name, LeaveType type, String country, String days, AccrualFrequency accrual) {
        LeavePolicy p = new LeavePolicy();
        p.setLeavePolicyId(id);
        p.setPolicyName(name);
        p.setLeaveType(type);
        p.setCountry(country);
        p.setAnnualEntitlement(new BigDecimal(days));
        p.setAccrualFrequency(accrual);
        p.setEffectiveFrom(POLICY_START);
        p.setPolicyStatus(PolicyStatus.ACTIVE);
        return p;
    }

    // ------------------------------------------------------------------ expense (31 policy versions)

    public static List<ExpensePolicy> expensePolicies() {
        List<ExpensePolicy> list = new ArrayList<>();
        for (String country : COUNTRIES) {
            for (ExpenseCategory category : ExpenseCategory.values()) {
                LocalDate from = POLICY_START;
                if (country.equals("SG") && category == ExpenseCategory.TRAINING) {
                    from = LocalDate.of(2026, 2, 1); // policy introduced later: claims before that date have no effective policy
                }
                if (country.equals("IN") && category == ExpenseCategory.MEALS) {
                    list.add(expense(country, category, "-V1", 0.8, POLICY_START, LocalDate.of(2026, 3, 31)));
                    from = LocalDate.of(2026, 4, 1);
                }
                list.add(expense(country, category, "", 1.0, from, null));
            }
        }
        return list;
    }

    private static ExpensePolicy expense(String country, ExpenseCategory category, String versionSuffix, double scale,
                                         LocalDate from, LocalDate to) {
        int[] usd = BusinessPolicyBaseline.CATEGORY_LIMITS_USD.get(category.name());
        String currency = BusinessPolicyBaseline.COUNTRY_CURRENCY.get(country);
        BigDecimal rate = new BigDecimal(BusinessPolicyBaseline.RATE_TO_USD.get(currency));
        double factor = BusinessPolicyBaseline.COUNTRY_LIMIT_FACTOR.get(country) * scale;
        ExpensePolicy p = new ExpensePolicy();
        p.setExpensePolicyId("XPOL-" + category.name() + "-" + country + versionSuffix);
        p.setPolicyName(category.name().replace('_', ' ') + " policy (" + country + (versionSuffix.isEmpty() ? "" : ", superseded") + ")");
        p.setExpenseCategory(category);
        p.setCountry(country);
        p.setEmployeeType(BusinessPolicyBaseline.FULL_TIME_ONLY_CATEGORIES.contains(category.name())
                ? PolicyEmployeeType.FULL_TIME : PolicyEmployeeType.ALL);
        p.setCurrency(currency);
        p.setMaximumPerTransaction(local(usd[0] * factor, rate));
        p.setMaximumDailyAmount(local(usd[1] * factor, rate).max(p.getMaximumPerTransaction()));
        p.setMaximumMonthlyAmount(local(usd[2] * factor, rate).max(p.getMaximumDailyAmount()));
        p.setReceiptRequired(true);
        p.setReceiptThreshold(usd[3] == 0 ? BigDecimal.ZERO.setScale(2) : local(usd[3] * factor, rate));
        p.setRequiresManagerApproval(true);
        p.setEffectiveFrom(from);
        p.setEffectiveTo(to);
        p.setPolicyStatus(PolicyStatus.ACTIVE);
        return p;
    }

    /** USD amount -> local currency, rounded to a "nice" policy number. */
    static BigDecimal local(double usdAmount, BigDecimal rateToUsd) {
        double localAmount = usdAmount / rateToUsd.doubleValue();
        double step = localAmount >= 1000 ? 100 : localAmount >= 100 ? 10 : 5;
        return BigDecimal.valueOf(Math.max(step, Math.round(localAmount / step) * step)).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ approval rules

    public static List<ApprovalRule> approvalRules() {
        List<ApprovalRule> r = new ArrayList<>();
        r.add(rule("AR-LV-001", RequestType.LEAVE, "0", "10", 1, ApproverRole.DIRECT_MANAGER, null, null, 100, true, null, "Leave up to 10 business days: direct manager"));
        r.add(rule("AR-LV-002", RequestType.LEAVE, "10", null, 1, ApproverRole.DIRECT_MANAGER, null, null, 100, true, null, "Long leave: direct manager first"));
        r.add(rule("AR-LV-003", RequestType.LEAVE, "10", null, 2, ApproverRole.DEPARTMENT_HEAD, null, null, 100, true, null, "Long leave: then department head"));
        r.add(rule("AR-EX-001", RequestType.EXPENSE, "0", "500", 1, ApproverRole.DIRECT_MANAGER, null, null, 100, true, null, "Up to 500 USD: direct manager"));
        r.add(rule("AR-EX-002", RequestType.EXPENSE, "500", "2000", 1, ApproverRole.MANAGER, null, null, 100, true, null, "500-2,000 USD: manager"));
        r.add(rule("AR-EX-003", RequestType.EXPENSE, "2000", "5000", 1, ApproverRole.MANAGER, null, null, 100, true, null, "2,000-5,000 USD: manager ..."));
        r.add(rule("AR-EX-004", RequestType.EXPENSE, "2000", "5000", 2, ApproverRole.FINANCE_REVIEWER, BusinessPolicyBaseline.FINANCE_MANAGER, null, 100, true, null, "... plus finance manager"));
        r.add(rule("AR-EX-005", RequestType.EXPENSE, "5000", null, 1, ApproverRole.MANAGER, null, null, 100, true, null, "Above 5,000 USD: manager ..."));
        r.add(rule("AR-EX-006", RequestType.EXPENSE, "5000", null, 2, ApproverRole.FINANCE_HEAD, BusinessPolicyBaseline.FINANCE_HEAD, null, 100, true, null, "... plus finance head"));
        r.add(rule("AR-EX-007", RequestType.EXPENSE, "0", "2000", 2, ApproverRole.FINANCE_REVIEWER, BusinessPolicyBaseline.FINANCE_MANAGER,
                ExpenseCategory.CLIENT_ENTERTAINMENT, 10, true, null, "Client entertainment: extra finance review (compliance)"));
        r.add(rule("AR-EX-008", RequestType.EXPENSE, "500", "2000", 2, ApproverRole.FINANCE_HEAD, BusinessPolicyBaseline.FINANCE_HEAD,
                null, 5, false, null, "Decommissioned CFO review rule - INACTIVE, must never be applied"));
        r.add(rule("AR-EX-009", RequestType.EXPENSE, "0", "500", 1, ApproverRole.DEPARTMENT_HEAD, null, null, 5, true, LocalDate.of(2025, 12, 31),
                "Superseded 2025 rule - expired, must never be applied"));
        return r;
    }

    private static ApprovalRule rule(String id, RequestType type, String min, String max, int level, ApproverRole role,
                                     String designation, ExpenseCategory category, int priority, boolean active,
                                     LocalDate effectiveTo, String description) {
        ApprovalRule r = new ApprovalRule();
        r.setApprovalRuleId(id);
        r.setWorkflowType(type);
        r.setAmountUnit(type == RequestType.LEAVE ? "DAYS" : "USD");
        r.setMinimumAmount(new BigDecimal(min));
        r.setMaximumAmount(max == null ? null : new BigDecimal(max));
        r.setApprovalLevel(level);
        r.setApproverRole(role);
        r.setApproverDesignation(designation);
        r.setExpenseCategory(category);
        r.setPriority(priority);
        r.setRuleStatus(active ? ApprovalRule.Status.ACTIVE : ApprovalRule.Status.INACTIVE);
        r.setEffectiveFrom(POLICY_START);
        r.setEffectiveTo(effectiveTo);
        r.setDescription(description);
        return r;
    }

    /** Documented department catalog: id -> [name, head designation, manager designation, lead designation]. */
    public static final Map<String, String[]> DEPARTMENTS = new java.util.LinkedHashMap<>();

    static {
        DEPARTMENTS.put("DEP-ENG", new String[] {"Engineering", "VP Engineering", "Engineering Manager", "Tech Lead"});
        DEPARTMENTS.put("DEP-PRD", new String[] {"Product", "VP Product", "Group Product Manager", "Product Lead"});
        DEPARTMENTS.put("DEP-HR", new String[] {"Human Resources", "VP Human Resources", "HR Manager", "HR Lead"});
        DEPARTMENTS.put("DEP-FIN", new String[] {"Finance", BusinessPolicyBaseline.FINANCE_HEAD, BusinessPolicyBaseline.FINANCE_MANAGER, "Finance Team Lead"});
        DEPARTMENTS.put("DEP-SAL", new String[] {"Sales", "VP Sales", "Sales Manager", "Sales Team Lead"});
        DEPARTMENTS.put("DEP-MKT", new String[] {"Marketing", "VP Marketing", "Marketing Manager", "Marketing Lead"});
        DEPARTMENTS.put("DEP-OPS", new String[] {"Operations", "VP Operations", "Operations Manager", "Operations Lead"});
        DEPARTMENTS.put("DEP-CS", new String[] {"Customer Success", "VP Customer Success", "Customer Success Manager", "Customer Success Lead"});
    }
}
