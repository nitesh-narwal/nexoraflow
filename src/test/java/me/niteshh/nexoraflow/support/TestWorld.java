package me.niteshh.nexoraflow.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.approval.ApprovalRule;
import me.niteshh.nexoraflow.approval.ApprovalRuleRepository;
import me.niteshh.nexoraflow.approval.ApproverRole;
import me.niteshh.nexoraflow.budget.Budget;
import me.niteshh.nexoraflow.budget.BudgetRepository;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.configuration.Country;
import me.niteshh.nexoraflow.configuration.CountryRepository;
import me.niteshh.nexoraflow.configuration.Currency;
import me.niteshh.nexoraflow.configuration.CurrencyRepository;
import me.niteshh.nexoraflow.costcenter.CostCenter;
import me.niteshh.nexoraflow.costcenter.CostCenterRepository;
import me.niteshh.nexoraflow.costcenter.CostCenterStatus;
import me.niteshh.nexoraflow.department.Department;
import me.niteshh.nexoraflow.department.DepartmentRepository;
import me.niteshh.nexoraflow.employee.AssignmentType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeCostCenter;
import me.niteshh.nexoraflow.employee.EmployeeCostCenterRepository;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.leave.LeaveProvisioningService;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.ExpensePolicyRepository;
import me.niteshh.nexoraflow.policy.Holiday;
import me.niteshh.nexoraflow.policy.HolidayCalendar;
import me.niteshh.nexoraflow.policy.HolidayCalendarRepository;
import me.niteshh.nexoraflow.policy.HolidayRepository;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicyRepository;
import me.niteshh.nexoraflow.policy.LeaveType;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;

/**
 * A small, hand-built company used by integration and acceptance tests (independent of the generator).
 *
 * <pre>
 * CEO (1)
 *  ├─ VP Engineering (2) ── Engineering Manager (4) ── Tech Lead (7) ── ALICE (8), BOB (9, joined 2026-06-01),
 *  │                         │                                         CONTRACTOR (10), TERMINATED (11)
 *  │                         └─ INDIA engineer (14)
 *  │                       └─ Inactive Manager (12, INACTIVE) ── ORPHAN (13)
 *  └─ VP Finance (3) ── Finance Manager (5), Finance Manager 2 (6)
 * </pre>
 * Everyone is US-based (USD) except employee 14 (India, INR). "Today" in tests is {@link #NOW}.
 */
@Component
public class TestWorld {

    public static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
    public static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    public static final String CEO = "EMP-000001";
    public static final String VP_ENG = "EMP-000002";
    public static final String VP_FIN = "EMP-000003";
    public static final String ENG_MGR = "EMP-000004";
    public static final String FIN_MGR = "EMP-000005";
    public static final String FIN_MGR_2 = "EMP-000006";
    public static final String LEAD = "EMP-000007";
    public static final String ALICE = "EMP-000008";
    public static final String BOB = "EMP-000009";
    public static final String CONTRACTOR = "EMP-000010";
    public static final String TERMINATED = "EMP-000011";
    public static final String INACTIVE_MGR = "EMP-000012";
    public static final String ORPHAN = "EMP-000013";
    public static final String INDIA = "EMP-000014";

    public static final String CC_MAIN = "CC-001";
    public static final String CC_OTHER = "CC-002";
    public static final String CC_CLOSED = "CC-003";
    public static final String CC_TIGHT = "CC-004";

    public static final String LP_ANNUAL_US = "LPOL-ANNUAL-US";
    public static final String LP_SICK = "LPOL-SICK";

    private final CurrencyRepository currencies;
    private final CountryRepository countries;
    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final CostCenterRepository costCenters;
    private final EmployeeCostCenterRepository assignments;
    private final BudgetRepository budgets;
    private final LeavePolicyRepository leavePolicies;
    private final ExpensePolicyRepository expensePolicies;
    private final ApprovalRuleRepository approvalRules;
    private final HolidayCalendarRepository calendars;
    private final HolidayRepository holidays;
    private final LeaveProvisioningService provisioning;
    private final IdService ids;

    public TestWorld(CurrencyRepository currencies, CountryRepository countries, DepartmentRepository departments,
                     EmployeeRepository employees, CostCenterRepository costCenters,
                     EmployeeCostCenterRepository assignments, BudgetRepository budgets,
                     LeavePolicyRepository leavePolicies, ExpensePolicyRepository expensePolicies,
                     ApprovalRuleRepository approvalRules, HolidayCalendarRepository calendars,
                     HolidayRepository holidays, LeaveProvisioningService provisioning, IdService ids) {
        this.currencies = currencies;
        this.countries = countries;
        this.departments = departments;
        this.employees = employees;
        this.costCenters = costCenters;
        this.assignments = assignments;
        this.budgets = budgets;
        this.leavePolicies = leavePolicies;
        this.expensePolicies = expensePolicies;
        this.approvalRules = approvalRules;
        this.calendars = calendars;
        this.holidays = holidays;
        this.provisioning = provisioning;
        this.ids = ids;
    }

    public void build() {
        AppClockGuard.run(() -> {
            currency("USD", "US Dollar", "1");
            currency("INR", "Indian Rupee", "0.012");
            currency("GBP", "Pound Sterling", "1.27");
            country("US", "United States", "USD");
            country("IN", "India", "INR");
            country("GB", "United Kingdom", "GBP");

            department("DEP-ENG", "Engineering");
            department("DEP-FIN", "Finance");

            employee(CEO, "Chloe", "Chief", "Chief Executive Officer", 1, null, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2015-01-05", null);
            employee(VP_ENG, "Victor", "Eng", "VP Engineering", 2, CEO, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2016-02-01", null);
            employee(VP_FIN, "Vera", "Fin", "VP Finance", 2, CEO, "DEP-FIN", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2016-03-01", null);
            employee(ENG_MGR, "Mona", "Manager", "Engineering Manager", 3, VP_ENG, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2018-04-01", null);
            employee(FIN_MGR, "Frank", "Finance", "Finance Manager", 3, VP_FIN, "DEP-FIN", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2018-05-01", null);
            employee(FIN_MGR_2, "Fiona", "Ledger", "Finance Manager", 3, VP_FIN, "DEP-FIN", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2019-05-01", null);
            employee(LEAD, "Leo", "Lead", "Tech Lead", 4, ENG_MGR, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2019-06-01", null);
            employee(ALICE, "Alice", "Anders", "Software Engineer", 5, LEAD, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2020-01-06", null);
            employee(BOB, "Bob", "Brown", "Software Engineer", 5, LEAD, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2026-06-01", null);
            employee(CONTRACTOR, "Connie", "Contract", "Contract Developer", 5, LEAD, "DEP-ENG", "US", EmploymentType.CONTRACTOR, EmploymentStatus.ACTIVE, "2025-01-06", null);
            employee(TERMINATED, "Terry", "Gone", "Software Engineer", 5, LEAD, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.TERMINATED, "2020-01-06", "2026-05-31");
            employee(INACTIVE_MGR, "Ivan", "Away", "Engineering Manager", 3, VP_ENG, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.INACTIVE, "2017-01-02", null);
            employee(ORPHAN, "Olive", "Orphan", "Software Engineer", 5, INACTIVE_MGR, "DEP-ENG", "US", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2021-01-04", null);
            employee(INDIA, "Indira", "Rao", "Software Engineer", 5, ENG_MGR, "DEP-ENG", "IN", EmploymentType.FULL_TIME, EmploymentStatus.ACTIVE, "2021-02-01", null);

            costCenter(CC_MAIN, "ENG-US-01", "DEP-ENG", "US", CostCenterStatus.ACTIVE);
            costCenter(CC_OTHER, "ENG-US-02", "DEP-ENG", "US", CostCenterStatus.ACTIVE);
            costCenter(CC_CLOSED, "ENG-US-99", "DEP-ENG", "US", CostCenterStatus.CLOSED);
            costCenter(CC_TIGHT, "ENG-US-TIGHT", "DEP-ENG", "US", CostCenterStatus.ACTIVE);
            for (String e : new String[] {CEO, VP_ENG, VP_FIN, ENG_MGR, FIN_MGR, FIN_MGR_2, LEAD, ALICE, BOB, CONTRACTOR, TERMINATED, INACTIVE_MGR, ORPHAN, INDIA}) {
                assign(e, CC_MAIN, AssignmentType.PRIMARY, "2015-01-01");
            }
            assign(ALICE, CC_TIGHT, AssignmentType.SECONDARY, "2026-01-01");
            assign(BOB, CC_TIGHT, AssignmentType.SECONDARY, "2026-01-01");

            for (int m = 1; m <= 12; m++) {
                budget(CC_MAIN, m, "100000.00", "USD");
                budget(CC_OTHER, m, "100000.00", "USD");
            }
            budget(CC_TIGHT, 10, "3000.00", "USD");

            ids.position("EMP", 15); // fixture IDs EMP-000001..14 exist; the counter continues after them
            leavePolicies();
            expensePolicies();
            approvalRules();
            holidays();

            for (Employee e : employees.findAllOrdered()) {
                if (e.getEmploymentStatus() != EmploymentStatus.INACTIVE) {
                    provisioning.provision(e, 2026, policy -> policy.getLeaveType() == LeaveType.ANNUAL ? new BigDecimal("2") : BigDecimal.ZERO);
                }
            }
        });
    }

    // ------------------------------------------------------------------ builders

    private void currency(String code, String name, String rate) {
        Currency c = new Currency();
        c.setCurrencyCode(code);
        c.setCurrencyName(name);
        c.setRateToReporting(new BigDecimal(rate));
        c.setRateEffectiveFrom(LocalDate.of(2026, 1, 1));
        c.setRateSource("test fixture");
        currencies.save(c);
    }

    private void country(String code, String name, String currency) {
        Country c = new Country();
        c.setCountryCode(code);
        c.setCountryName(name);
        c.setCurrencyCode(currency);
        countries.save(c);
    }

    private void department(String id, String name) {
        Department d = new Department();
        d.setDepartmentId(id);
        d.setDepartmentName(name);
        departments.save(d);
    }

    private void employee(String id, String first, String last, String designation, int level, String manager, String dept,
                          String country, EmploymentType type, EmploymentStatus status, String joined, String terminated) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail((first + "." + last).toLowerCase() + "@nexora-technologies.example");
        e.setDepartmentId(dept);
        e.setDesignation(designation);
        e.setJobLevel(level);
        e.setManagerId(manager);
        e.setCountry(country);
        e.setLocation("Test City");
        e.setEmploymentType(type);
        e.setEmploymentStatus(status);
        e.setJoiningDate(LocalDate.parse(joined));
        e.setTerminationDate(terminated == null ? null : LocalDate.parse(terminated));
        employees.save(e);
    }

    private void costCenter(String id, String code, String dept, String country, CostCenterStatus status) {
        CostCenter c = new CostCenter();
        c.setCostCenterId(id);
        c.setCostCenterCode(code);
        c.setCostCenterName("Cost center " + code);
        c.setDepartmentId(dept);
        c.setCountry(country);
        c.setStatus(status);
        costCenters.save(c);
    }

    private void assign(String employee, String costCenter, AssignmentType type, String from) {
        EmployeeCostCenter a = new EmployeeCostCenter();
        a.setAssignmentId(ids.costCenterAssignmentId());
        a.setEmployeeId(employee);
        a.setCostCenterId(costCenter);
        a.setAssignmentType(type);
        a.setEffectiveFrom(LocalDate.parse(from));
        assignments.save(a);
        if (type == AssignmentType.PRIMARY) {
            employees.findById(employee).ifPresent(e -> {
                e.setCostCenterId(costCenter);
                employees.save(e);
            });
        }
    }

    private void budget(String costCenter, int month, String amount, String currency) {
        Budget b = new Budget();
        b.setBudgetId("BUD-" + costCenter + "-2026-" + String.format("%02d", month));
        b.setCostCenterId(costCenter);
        b.setFiscalYear(2026);
        b.setPeriodStart(LocalDate.of(2026, month, 1));
        b.setPeriodEnd(LocalDate.of(2026, month, 1).withDayOfMonth(LocalDate.of(2026, month, 1).lengthOfMonth()));
        b.setAllocatedAmount(new BigDecimal(amount));
        b.setCurrency(currency);
        budgets.save(b);
    }

    private void leavePolicies() {
        LeavePolicy annual = new LeavePolicy();
        annual.setLeavePolicyId(LP_ANNUAL_US);
        annual.setPolicyName("Annual leave (US)");
        annual.setLeaveType(LeaveType.ANNUAL);
        annual.setCountry("US");
        annual.setAnnualEntitlement(new BigDecimal("20"));
        annual.setAccrualFrequency(AccrualFrequency.ANNUAL_UPFRONT);
        annual.setCarryForwardLimit(new BigDecimal("5"));
        annual.setMaximumConsecutiveDays(15);
        annual.setMinimumNoticeDays(7);
        annual.setEligibleEmploymentTypes("FULL_TIME,PART_TIME");
        annual.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        leavePolicies.save(annual);

        LeavePolicy sick = new LeavePolicy();
        sick.setLeavePolicyId(LP_SICK);
        sick.setPolicyName("Sick leave (global)");
        sick.setLeaveType(LeaveType.SICK);
        sick.setAnnualEntitlement(new BigDecimal("10"));
        sick.setAccrualFrequency(AccrualFrequency.ANNUAL_UPFRONT);
        sick.setMaximumConsecutiveDays(10);
        sick.setMinimumNoticeDays(0);
        sick.setRequiresDocumentation(true);
        sick.setDocumentationThresholdDays(2);
        sick.setEligibleEmploymentTypes("FULL_TIME,PART_TIME,INTERN");
        sick.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        leavePolicies.save(sick);
    }

    private void expensePolicies() {
        expensePolicy("US", "USD", ExpenseCategory.MEALS, PolicyEmployeeType.ALL, "75", "120", "1000", "25");
        expensePolicy("US", "USD", ExpenseCategory.TRAVEL, PolicyEmployeeType.ALL, "2500", "3000", "8000", "0");
        expensePolicy("US", "USD", ExpenseCategory.OFFICE_SUPPLIES, PolicyEmployeeType.ALL, "400", "800", "1000", "20");
        expensePolicy("US", "USD", ExpenseCategory.CLIENT_ENTERTAINMENT, PolicyEmployeeType.FULL_TIME, "600", "800", "2000", "0");
        expensePolicy("US", "USD", ExpenseCategory.TRAINING, PolicyEmployeeType.FULL_TIME, "3000", "3000", "6000", "0");
        // India: INR limits, so that an Indian employee's claim is consistent in currency
        expensePolicy("IN", "INR", ExpenseCategory.MEALS, PolicyEmployeeType.ALL, "4000", "6000", "50000", "1500");
        expensePolicy("IN", "INR", ExpenseCategory.TRAVEL, PolicyEmployeeType.ALL, "100000", "150000", "500000", "0");
    }

    private void expensePolicy(String country, String currency, ExpenseCategory category, PolicyEmployeeType type,
                               String perTxn, String daily, String monthly, String receiptThreshold) {
        ExpensePolicy p = new ExpensePolicy();
        p.setExpensePolicyId("XPOL-" + category + "-" + country + (type == PolicyEmployeeType.ALL ? "" : "-" + type));
        p.setPolicyName(category + " (" + country + ")");
        p.setExpenseCategory(category);
        p.setCountry(country);
        p.setEmployeeType(type);
        p.setCurrency(currency);
        p.setMaximumPerTransaction(new BigDecimal(perTxn));
        p.setMaximumDailyAmount(new BigDecimal(daily));
        p.setMaximumMonthlyAmount(new BigDecimal(monthly));
        p.setReceiptRequired(true);
        p.setReceiptThreshold(new BigDecimal(receiptThreshold));
        p.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        expensePolicies.save(p);
    }

    private void approvalRules() {
        rule("AR-LV-001", RequestType.LEAVE, "DAYS", "0", "10", 1, ApproverRole.DIRECT_MANAGER, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-LV-002", RequestType.LEAVE, "DAYS", "10", null, 1, ApproverRole.DIRECT_MANAGER, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-LV-003", RequestType.LEAVE, "DAYS", "10", null, 2, ApproverRole.DEPARTMENT_HEAD, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-001", RequestType.EXPENSE, "USD", "0", "500", 1, ApproverRole.DIRECT_MANAGER, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-002", RequestType.EXPENSE, "USD", "500", "2000", 1, ApproverRole.MANAGER, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-003", RequestType.EXPENSE, "USD", "2000", "5000", 1, ApproverRole.MANAGER, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-004", RequestType.EXPENSE, "USD", "2000", "5000", 2, ApproverRole.FINANCE_REVIEWER, "Finance Manager", null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-005", RequestType.EXPENSE, "USD", "5000", null, 1, ApproverRole.MANAGER, null, null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-006", RequestType.EXPENSE, "USD", "5000", null, 2, ApproverRole.FINANCE_HEAD, "VP Finance", null, 100, ApprovalRule.Status.ACTIVE);
        rule("AR-EX-007", RequestType.EXPENSE, "USD", "0", "2000", 2, ApproverRole.FINANCE_REVIEWER, "Finance Manager", ExpenseCategory.CLIENT_ENTERTAINMENT, 10, ApprovalRule.Status.ACTIVE);
        // decommissioned rule: must never be applied
        rule("AR-EX-009", RequestType.EXPENSE, "USD", "500", "2000", 2, ApproverRole.FINANCE_HEAD, "VP Finance", null, 5, ApprovalRule.Status.INACTIVE);
    }

    private void rule(String id, RequestType type, String unit, String min, String max, int level, ApproverRole role,
                      String designation, ExpenseCategory category, int priority, ApprovalRule.Status status) {
        ApprovalRule r = new ApprovalRule();
        r.setApprovalRuleId(id);
        r.setWorkflowType(type);
        r.setAmountUnit(unit);
        r.setMinimumAmount(new BigDecimal(min));
        r.setMaximumAmount(max == null ? null : new BigDecimal(max));
        r.setApprovalLevel(level);
        r.setApproverRole(role);
        r.setApproverDesignation(designation);
        r.setExpenseCategory(category);
        r.setPriority(priority);
        r.setRuleStatus(status);
        r.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        r.setDescription("test rule " + id);
        approvalRules.save(r);
    }

    private void holidays() {
        HolidayCalendar cal = new HolidayCalendar();
        cal.setCalendarId("HC-US-2026");
        cal.setCountry("US");
        cal.setCalendarYear(2026);
        cal.setCalendarName("US public holidays 2026");
        calendars.save(cal);
        holiday(cal, 1, "2026-07-03", "Independence Day (observed)");
        holiday(cal, 2, "2026-11-26", "Thanksgiving");
        holiday(cal, 3, "2026-12-25", "Christmas Day");
    }

    private void holiday(HolidayCalendar cal, int n, String date, String name) {
        Holiday h = new Holiday();
        h.setHolidayId("HOL-US-2026-" + String.format("%02d", n));
        h.setCalendarId(cal.getCalendarId());
        h.setHolidayDate(LocalDate.parse(date));
        h.setHolidayName(name);
        holidays.save(h);
    }

    /** Runs fixture creation with the clock pinned so audit-free timestamps are stable. */
    private static final class AppClockGuard {
        static void run(Runnable r) {
            me.niteshh.nexoraflow.common.AppClock.setSimulatedTime(NOW);
            try {
                r.run();
            } finally {
                me.niteshh.nexoraflow.common.AppClock.clearSimulatedTime();
            }
        }
    }
}
