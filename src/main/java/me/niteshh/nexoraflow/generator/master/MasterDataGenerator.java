package me.niteshh.nexoraflow.generator.master;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import me.niteshh.nexoraflow.approval.ApprovalRuleRepository;
import me.niteshh.nexoraflow.budget.Budget;
import me.niteshh.nexoraflow.budget.BudgetRepository;
import me.niteshh.nexoraflow.common.AppClock;
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
import me.niteshh.nexoraflow.generator.GeneratorSeeds;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.catalog.BusinessPolicyBaseline;
import me.niteshh.nexoraflow.generator.catalog.HolidayCatalog;
import me.niteshh.nexoraflow.generator.catalog.PolicyCatalog;
import me.niteshh.nexoraflow.leave.LeaveProvisioningService;
import me.niteshh.nexoraflow.policy.ExpensePolicyRepository;
import me.niteshh.nexoraflow.policy.Holiday;
import me.niteshh.nexoraflow.policy.HolidayCalendar;
import me.niteshh.nexoraflow.policy.HolidayCalendarRepository;
import me.niteshh.nexoraflow.policy.HolidayRepository;
import me.niteshh.nexoraflow.policy.LeavePolicyRepository;

/**
 * Generation steps 1-14: company configuration, countries, departments, employees and hierarchy, cost
 * centers and assignments, policies, approval rules, budgets, policy assignments, leave balances and holiday
 * calendars. Everything is created in dependency order inside one transaction, with the application clock
 * pinned to the master-data snapshot date so that no wall-clock value reaches the database.
 */
@Service
public class MasterDataGenerator {

    public record MasterSummary(int employees, int costCenters, int budgets, int leaveBalances, int holidays) {
    }

    private static final Map<String, String> CURRENCY_NAMES = Map.of("USD", "US Dollar", "INR", "Indian Rupee",
            "GBP", "Pound Sterling", "EUR", "Euro", "SGD", "Singapore Dollar");
    private static final Map<String, Integer> PER_HEAD_MONTHLY_BUDGET_USD = Map.of(
            "DEP-ENG", 450, "DEP-PRD", 300, "DEP-HR", 150, "DEP-FIN", 150, "DEP-SAL", 900, "DEP-MKT", 450, "DEP-OPS", 350, "DEP-CS", 300);
    private static final String[] COUNTRY_ORDER = {"IN", "US", "GB", "DE", "SG"};

    private final CurrencyRepository currencies;
    private final CountryRepository countries;
    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final CostCenterRepository costCenters;
    private final EmployeeCostCenterRepository assignments;
    private final LeavePolicyRepository leavePolicies;
    private final ExpensePolicyRepository expensePolicies;
    private final ApprovalRuleRepository approvalRules;
    private final BudgetRepository budgets;
    private final HolidayCalendarRepository calendars;
    private final HolidayRepository holidays;
    private final LeaveProvisioningService provisioning;
    private final IdService ids;
    private final TransactionTemplate tx;

    public MasterDataGenerator(CurrencyRepository currencies, CountryRepository countries, DepartmentRepository departments,
                               EmployeeRepository employees, CostCenterRepository costCenters,
                               EmployeeCostCenterRepository assignments, LeavePolicyRepository leavePolicies,
                               ExpensePolicyRepository expensePolicies, ApprovalRuleRepository approvalRules,
                               BudgetRepository budgets, HolidayCalendarRepository calendars, HolidayRepository holidays,
                               LeaveProvisioningService provisioning, IdService ids, PlatformTransactionManager txManager) {
        this.currencies = currencies;
        this.countries = countries;
        this.departments = departments;
        this.employees = employees;
        this.costCenters = costCenters;
        this.assignments = assignments;
        this.leavePolicies = leavePolicies;
        this.expensePolicies = expensePolicies;
        this.approvalRules = approvalRules;
        this.budgets = budgets;
        this.calendars = calendars;
        this.holidays = holidays;
        this.provisioning = provisioning;
        this.ids = ids;
        this.tx = new TransactionTemplate(txManager);
    }

    public MasterSummary generate(GeneratorSettings settings, GeneratorSeeds seeds) {
        AppClock.setSimulatedTime(settings.masterDataInstant());
        try {
            return tx.execute(status -> doGenerate(settings, seeds));
        } finally {
            AppClock.clearSimulatedTime();
        }
    }

    private MasterSummary doGenerate(GeneratorSettings settings, GeneratorSeeds seeds) {
        // 1-2. company configuration, countries and currencies
        BusinessPolicyBaseline.RATE_TO_USD.keySet().stream().sorted().forEach(code -> {
            Currency c = new Currency();
            c.setCurrencyCode(code);
            c.setCurrencyName(CURRENCY_NAMES.get(code));
            c.setRateToReporting(new BigDecimal(BusinessPolicyBaseline.RATE_TO_USD.get(code)));
            c.setRateEffectiveFrom(settings.masterDataDate());
            c.setRateSource("Fixed illustrative rate (documented assumption, not market data)");
            currencies.save(c);
        });
        for (String code : COUNTRY_ORDER) {
            Country c = new Country();
            c.setCountryCode(code);
            c.setCountryName(BusinessPolicyBaseline.COUNTRY_NAME.get(code));
            c.setCurrencyCode(BusinessPolicyBaseline.COUNTRY_CURRENCY.get(code));
            countries.save(c);
        }

        // 3. departments
        PolicyCatalog.DEPARTMENTS.forEach((id, def) -> {
            Department d = new Department();
            d.setDepartmentId(id);
            d.setDepartmentName(def[0]);
            departments.save(d);
        });

        // 4-5. employees and hierarchy (parents first) + department heads
        List<Employee> staff = new OrgChartBuilder(settings, seeds).build();
        employees.saveAll(staff);
        Map<String, Employee> byId = new HashMap<>();
        staff.forEach(e -> byId.put(e.getEmployeeId(), e));
        for (Employee e : staff) {
            if (e.getJobLevel() == 2) {
                Department d = departments.findById(e.getDepartmentId()).orElseThrow();
                d.setHeadEmployeeId(e.getEmployeeId());
                departments.save(d);
            }
        }
        ids.position("EMP", staff.size() + 1L);

        // 6. cost centers
        Map<String, String> costCenterByDeptCountry = new LinkedHashMap<>();
        Map<String, CostCenter> allCostCenters = new LinkedHashMap<>();
        int ccNumber = 1;
        for (String dept : PolicyCatalog.DEPARTMENTS.keySet()) {
            for (String country : COUNTRY_ORDER) {
                final String d = dept;
                final String c = country;
                boolean present = staff.stream().anyMatch(e -> e.getDepartmentId().equals(d) && e.getCountry().equals(c));
                if (present) {
                    CostCenter cc = costCenter(ccNumber++, deptPrefix(dept) + "-" + country + "-01",
                            PolicyCatalog.DEPARTMENTS.get(dept)[0] + " " + BusinessPolicyBaseline.COUNTRY_NAME.get(country),
                            dept, country, CostCenterStatus.ACTIVE);
                    costCenterByDeptCountry.put(dept + "|" + country, cc.getCostCenterId());
                    allCostCenters.put(cc.getCostCenterId(), cc);
                }
            }
        }
        CostCenter atlas = costCenter(ccNumber++, "PRJ-ATLAS", "Project Atlas (shared)", "DEP-PRD", "US", CostCenterStatus.ACTIVE);
        CostCenter helios = costCenter(ccNumber++, "PRJ-HELIOS", "Project Helios (shared)", "DEP-ENG", "IN", CostCenterStatus.ACTIVE);
        CostCenter legacyUs = costCenter(ccNumber++, "LEG-OLD-01", "Legacy Operations (closed)", "DEP-OPS", "US", CostCenterStatus.CLOSED);
        CostCenter legacyGb = costCenter(ccNumber++, "LEG-OLD-02", "Legacy Sales UK (closed)", "DEP-SAL", "GB", CostCenterStatus.CLOSED);
        CostCenter frozen = costCenter(ccNumber++, "SUS-FREEZE-01", "Marketing Germany (frozen)", "DEP-MKT", "DE", CostCenterStatus.SUSPENDED);
        List<CostCenter> special = List.of(atlas, helios, legacyUs, legacyGb, frozen);
        special.forEach(cc -> allCostCenters.put(cc.getCostCenterId(), cc));
        int tightCount = settings.profile().scaled(6) + 1;
        List<CostCenter> tight = new ArrayList<>();
        for (int i = 1; i <= tightCount; i++) {
            CostCenter cc = costCenter(ccNumber++, String.format("SPL-%02d", i), "Special project " + i + " (tight budget)",
                    "DEP-ENG", "US", CostCenterStatus.ACTIVE);
            tight.add(cc);
            allCostCenters.put(cc.getCostCenterId(), cc);
        }
        for (CostCenter cc : allCostCenters.values()) {
            Department d = departments.findById(cc.getDepartmentId()).orElseThrow();
            cc.setBudgetOwnerId(d.getHeadEmployeeId());
        }
        costCenters.saveAll(allCostCenters.values());

        // 7. employee <-> cost-center assignments (history, secondary authorisations)
        Random rng = seeds.random("master|assignments");
        Map<String, String> currentPrimary = new HashMap<>();
        for (Employee e : staff) {
            String base = costCenterByDeptCountry.get(e.getDepartmentId() + "|" + e.getCountry());
            boolean transfer = e.getJobLevel() == 5 && e.getEmploymentStatus() == EmploymentStatus.ACTIVE
                    && e.getJoiningDate().isBefore(LocalDate.of(2025, 12, 1)) && rng.nextInt(100) < 6;
            if (transfer) {
                List<String> others = new ArrayList<>();
                costCenterByDeptCountry.forEach((k, v) -> {
                    if (k.startsWith(e.getDepartmentId() + "|") && !v.equals(base)) {
                        others.add(v);
                    }
                });
                if (!others.isEmpty()) {
                    String target = others.get(rng.nextInt(others.size()));
                    LocalDate transferDate = LocalDate.of(settings.year(), 2 + rng.nextInt(6), 1);
                    assign(e.getEmployeeId(), base, AssignmentType.PRIMARY, e.getJoiningDate(), transferDate.minusDays(1));
                    assign(e.getEmployeeId(), target, AssignmentType.PRIMARY, transferDate, null);
                    currentPrimary.put(e.getEmployeeId(), target);
                    continue;
                }
            }
            assign(e.getEmployeeId(), base, AssignmentType.PRIMARY, e.getJoiningDate(), null);
            currentPrimary.put(e.getEmployeeId(), base);
        }
        for (Employee e : staff) {
            e.setCostCenterId(currentPrimary.get(e.getEmployeeId()));
            if (e.getJobLevel() == 5 && e.getEmploymentStatus() == EmploymentStatus.ACTIVE && rng.nextInt(100) < 8) {
                CostCenter project = rng.nextBoolean() ? atlas : helios;
                assign(e.getEmployeeId(), project.getCostCenterId(), AssignmentType.SECONDARY, settings.masterDataDate(), null);
            }
        }
        ReservedPool pool = new ReservedPool(staff);
        List<Employee> members = pool.members();
        for (int i = 0; i < members.size(); i++) {
            CostCenter t = tight.get(i % tight.size());
            assign(members.get(i).getEmployeeId(), t.getCostCenterId(), AssignmentType.SECONDARY, settings.masterDataDate(), null);
        }
        employees.saveAll(staff);

        // 8-10. leave policies, expense policies, approval rules
        leavePolicies.saveAll(PolicyCatalog.leavePolicies());
        expensePolicies.saveAll(PolicyCatalog.expensePolicies());
        approvalRules.saveAll(PolicyCatalog.approvalRules());

        // 11. budgets (monthly, derived from headcount drivers - never from random "remaining" values)
        Random budgetRng = seeds.random("master|budgets");
        Map<String, Integer> headcount = new HashMap<>();
        staff.stream().filter(e -> e.getEmploymentStatus() == EmploymentStatus.ACTIVE)
                .forEach(e -> headcount.merge(currentPrimary.get(e.getEmployeeId()), 1, Integer::sum));
        int budgetCount = 0;
        List<Budget> budgetRows = new ArrayList<>();
        for (CostCenter cc : allCostCenters.values()) {
            if (cc.getStatus() != CostCenterStatus.ACTIVE) {
                continue;
            }
            String currency = BusinessPolicyBaseline.COUNTRY_CURRENCY.get(cc.getCountry());
            BigDecimal rate = new BigDecimal(BusinessPolicyBaseline.RATE_TO_USD.get(currency));
            for (int month = 1; month <= 12; month++) {
                double usd;
                if (tight.contains(cc)) {
                    usd = month == settings.referenceDate().getMonthValue() ? 3000 : 50_000;
                } else if (cc == atlas || cc == helios) {
                    usd = 25_000;
                } else {
                    int heads = Math.max(1, headcount.getOrDefault(cc.getCostCenterId(), 1));
                    usd = Math.max(5000, heads * PER_HEAD_MONTHLY_BUDGET_USD.get(cc.getDepartmentId()) * (0.9 + budgetRng.nextDouble() * 0.2));
                }
                budgetRows.add(budget(cc, settings.year(), month, usd, currency, rate));
                budgetCount++;
            }
        }
        budgets.saveAll(budgetRows);

        // 12-13. policy assignments and leave balances (shared provisioning logic with the employee API)
        Random carryRng = seeds.random("master|carry-forward");
        int balances = 0;
        for (Employee e : staff) {
            balances += provisioning.provision(e, settings.year(), policy -> {
                int steps = policy.getCarryForwardLimit().multiply(BigDecimal.valueOf(2)).intValue();
                return steps <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(carryRng.nextInt(steps + 1)).divide(BigDecimal.valueOf(2), 1, RoundingMode.UNNECESSARY);
            }).size();
        }

        // 14. holiday calendars
        int holidayCount = 0;
        for (String country : COUNTRY_ORDER) {
            for (int year = settings.year() - 1; year <= settings.year() + 1; year++) {
                HolidayCalendar cal = new HolidayCalendar();
                cal.setCalendarId("HC-" + country + "-" + year);
                cal.setCountry(country);
                cal.setCalendarYear(year);
                cal.setCalendarName(BusinessPolicyBaseline.COUNTRY_NAME.get(country) + " public holidays " + year);
                calendars.save(cal);
                int n = 1;
                for (HolidayCatalog.NamedHoliday nh : HolidayCatalog.holidays(country, year)) {
                    Holiday h = new Holiday();
                    h.setHolidayId("HOL-" + country + "-" + year + "-" + String.format("%02d", n++));
                    h.setCalendarId(cal.getCalendarId());
                    h.setHolidayDate(nh.date());
                    h.setHolidayName(nh.name());
                    holidays.save(h);
                    holidayCount++;
                }
            }
        }
        return new MasterSummary(staff.size(), allCostCenters.size(), budgetCount, balances, holidayCount);
    }

    private CostCenter costCenter(int number, String code, String name, String dept, String country, CostCenterStatus status) {
        CostCenter cc = new CostCenter();
        cc.setCostCenterId("CC-" + String.format("%04d", number));
        cc.setCostCenterCode(code);
        cc.setCostCenterName(name);
        cc.setDepartmentId(dept);
        cc.setCountry(country);
        cc.setStatus(status);
        return cc;
    }

    private void assign(String employeeId, String costCenterId, AssignmentType type, LocalDate from, LocalDate to) {
        EmployeeCostCenter a = new EmployeeCostCenter();
        a.setAssignmentId(ids.costCenterAssignmentId());
        a.setEmployeeId(employeeId);
        a.setCostCenterId(costCenterId);
        a.setAssignmentType(type);
        a.setEffectiveFrom(from);
        a.setEffectiveTo(to);
        assignments.save(a);
    }

    private Budget budget(CostCenter cc, int year, int month, double usd, String currency, BigDecimal rateToUsd) {
        Budget b = new Budget();
        YearMonth ym = YearMonth.of(year, month);
        b.setBudgetId("BUD-" + cc.getCostCenterId() + "-" + ym);
        b.setCostCenterId(cc.getCostCenterId());
        b.setFiscalYear(year);
        b.setPeriodStart(ym.atDay(1));
        b.setPeriodEnd(ym.atEndOfMonth());
        BigDecimal local = BigDecimal.valueOf(usd).divide(rateToUsd, 2, RoundingMode.HALF_UP);
        BigDecimal rounded = local.divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2);
        b.setAllocatedAmount(rounded);
        b.setCurrency(currency);
        b.setCommitPending(true);
        return b;
    }

    private static String deptPrefix(String departmentId) {
        return departmentId.substring(4);
    }

    /** Distinct, ordered view used by tests and reports. */
    public static Map<String, Integer> countByDepartment(List<Employee> staff) {
        Map<String, Integer> m = new TreeMap<>();
        staff.forEach(e -> m.merge(e.getDepartmentId(), 1, Integer::sum));
        return m;
    }
}
