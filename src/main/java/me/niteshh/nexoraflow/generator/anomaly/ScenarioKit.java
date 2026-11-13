package me.niteshh.nexoraflow.generator.anomaly;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.expense.ExpenseSubmission;
import me.niteshh.nexoraflow.generator.plan.ApprovalOracle;
import me.niteshh.nexoraflow.generator.plan.ClaimFactory;
import me.niteshh.nexoraflow.generator.plan.Plans.ClaimPlan;
import me.niteshh.nexoraflow.generator.plan.Plans.LeavePlan;
import me.niteshh.nexoraflow.generator.plan.Plans.Plan;
import me.niteshh.nexoraflow.generator.plan.Plans.TruthSpec;
import me.niteshh.nexoraflow.generator.plan.PlanningContext;
import me.niteshh.nexoraflow.generator.plan.Timeline;
import me.niteshh.nexoraflow.leave.LeaveSubmission;

/** Shared toolbox for the anomaly scenario builders: subjects, dates, plan factories and label helpers. */
public final class ScenarioKit {

    public final PlanningContext ctx;
    public final ClaimFactory factory;
    public final Random rng;
    public final LocalDate ref;
    public final Instant cutoff;
    public final List<Plan> plans = new ArrayList<>();
    private int poolCursor;
    private int scenarioCounter;
    /** Employees whose leave balance a scenario deliberately exhausted; no later scenario may pick them as a subject. */
    private final java.util.Set<String> balanceDepleted = new java.util.HashSet<>();
    /** "employee|CATEGORY|yyyy-MM": every category and month in which an employee already carries a big-ticket scenario (monthly limits are finite). */
    private final java.util.Set<String> bigTicketMonths = new java.util.HashSet<>();

    public ScenarioKit(PlanningContext ctx) {
        this.ctx = ctx;
        this.factory = new ClaimFactory(ctx);
        this.rng = ctx.seeds.random("anomalies");
        this.ref = ctx.settings.referenceDate();
        this.cutoff = ctx.settings.cutoff();
    }

    public int count(int mediumCount) {
        return ctx.settings.profile().scaled(mediumCount);
    }

    public String nextScenarioId(String code) {
        return "SCN-" + code + "-" + String.format("%03d", ++scenarioCounter);
    }

    // ------------------------------------------------------------------ subjects

    /**
     * Next employee from the reserved pool, optionally restricted by a predicate (round robin). Employees whose leave
     * balance an earlier scenario exhausted are only used when nobody else satisfies the predicate (a small pool cannot
     * afford to exclude them: their expense scenarios are unaffected).
     */
    public Optional<Employee> pool(java.util.function.Predicate<Employee> filter) {
        Optional<Employee> pick = scan(filter.and(e -> !balanceDepleted.contains(e.getEmployeeId())));
        return pick.isPresent() ? pick : scan(filter);
    }

    private Optional<Employee> scan(java.util.function.Predicate<Employee> filter) {
        List<Employee> members = ctx.pool.members();
        for (int i = 0; i < members.size(); i++) {
            Employee e = members.get((poolCursor + i) % members.size());
            if (filter.test(e)) {
                poolCursor = (poolCursor + i + 1) % members.size();
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * Next pool employee for a scenario with large spend in the given expense categories between {@code from} and
     * {@code to}. Monthly limits are finite per employee and category (for example 8,000 USD of TRAVEL), so an employee
     * carries at most one such scenario per category and month; two of them could exhaust the monthly limit and the second
     * claim would be rejected for a reason the scenario did not intend. When the pool is exhausted for those months the
     * reuse is unavoidable and the plain round robin applies.
     */
    public Optional<Employee> bigTicketSubject(java.util.function.Predicate<Employee> filter, LocalDate from, LocalDate to, String... categories) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (String category : categories) {
            keys.add(category + "|" + java.time.YearMonth.from(from));
            keys.add(category + "|" + java.time.YearMonth.from(to));
        }
        Optional<Employee> pick = pool(x -> filter.test(x) && keys.stream().noneMatch(key -> bigTicketMonths.contains(x.getEmployeeId() + "|" + key)));
        if (pick.isEmpty()) {
            pick = pool(filter);
        }
        pick.ifPresent(e -> keys.forEach(key -> bigTicketMonths.add(e.getEmployeeId() + "|" + key)));
        return pick;
    }

    /** Records that a scenario used up (most of) the employee's leave balance, so later scenarios must not reuse them. */
    public void markBalanceDepleted(Employee e) {
        balanceDepleted.add(e.getEmployeeId());
    }

    public Employee anyPool() {
        return pool(e -> true).orElseThrow(() -> new IllegalStateException("Reserved pool is empty; dataset too small for scenarios"));
    }

    public List<Employee> filter(java.util.function.Predicate<Employee> p) {
        return ctx.org.all().stream().filter(p).toList();
    }

    public List<Employee> employeesWithStatus(EmploymentStatus status) {
        return filter(e -> e.getEmploymentStatus() == status && e.getJobLevel() == 5);
    }

    public List<Employee> contractors() {
        return filter(e -> e.getEmploymentType() == EmploymentType.CONTRACTOR && e.getEmploymentStatus() == EmploymentStatus.ACTIVE
                && !ctx.pool.unavailableChain().contains(e.getEmployeeId()));
    }

    /** The cost center reserved for the employee's big-ticket scenarios: their tight secondary assignment. */
    public Optional<String> scenarioCostCenter(Employee e) {
        return ctx.assignments.stream()
                .filter(a -> a.getEmployeeId().equals(e.getEmployeeId()) && a.getAssignmentType() == me.niteshh.nexoraflow.employee.AssignmentType.SECONDARY)
                .map(a -> a.getCostCenterId())
                .filter(id -> ctx.costCenters.get(id).getCostCenterCode().startsWith("SPL-")).findFirst();
    }

    // ------------------------------------------------------------------ dates

    public LocalDate weekday(LocalDate d) {
        LocalDate x = d;
        while (x.getDayOfWeek() == DayOfWeek.SATURDAY || x.getDayOfWeek() == DayOfWeek.SUNDAY) {
            x = x.plusDays(1);
        }
        return x;
    }

    /** A weekday between {@code fromDaysBeforeRef} and {@code toDaysBeforeRef} days before the reference date. */
    public LocalDate dayBeforeRef(int fromDaysBeforeRef, int toDaysBeforeRef) {
        int span = Math.max(1, fromDaysBeforeRef - toDaysBeforeRef);
        return weekday(ref.minusDays(fromDaysBeforeRef).plusDays(rng.nextInt(span)));
    }

    public Instant submitTime(LocalDate date) {
        return Timeline.at(date, 8 + rng.nextInt(9), rng.nextInt(60));
    }

    // ------------------------------------------------------------------ plan factories

    public LeavePlan leave(Employee e, String policyId, LocalDate start, LocalDate end, Instant submitAt, String doc) {
        LeavePlan p = new LeavePlan(ctx.nextLeaveKey(), e.getEmployeeId(), submitAt,
                new LeaveSubmission(e.getEmployeeId(), policyId, start, end, null, "Scenario request", doc));
        plans.add(p);
        return p;
    }

    public ClaimPlan claim(Employee e, String costCenterRef, String currency, Instant submitAt, List<ExpenseSubmission.Line> lines,
                           LocalDate periodStart, LocalDate periodEnd) {
        ClaimPlan p = new ClaimPlan(ctx.nextClaimKey(), e.getEmployeeId(), submitAt,
                new ExpenseSubmission(e.getEmployeeId(), periodStart, periodEnd, costCenterRef, currency, lines));
        plans.add(p);
        return p;
    }

    /** A claim whose period spans exactly its line dates. */
    public ClaimPlan claim(Employee e, String costCenterRef, Instant submitAt, ExpenseSubmission.Line... lines) {
        LocalDate min = null;
        LocalDate max = null;
        for (ExpenseSubmission.Line l : lines) {
            min = min == null || l.transactionDate().isBefore(min) ? l.transactionDate() : min;
            max = max == null || l.transactionDate().isAfter(max) ? l.transactionDate() : max;
        }
        return claim(e, costCenterRef, factory.currencyOf(e), submitAt, List.of(lines), min, max);
    }

    public ExpenseSubmission.Line line(Employee e, String category, String merchant, LocalDate date, double usd, boolean receipt, String seed) {
        BigDecimal amount = factory.local(factory.currencyOf(e), usd);
        return new ExpenseSubmission.Line(category, merchant, date, factory.description(category, rng), amount, factory.currencyOf(e), null,
                receipt ? factory.receipt(seed, rng) : null);
    }

    public ExpenseSubmission.Line lineLocal(String category, String merchant, LocalDate date, BigDecimal amount, String currency,
                                            ExpenseSubmission.ReceiptUpload receipt, String receiptRef) {
        return new ExpenseSubmission.Line(category, merchant, date, factory.description(category, rng), amount, currency, receiptRef, receipt);
    }

    // ------------------------------------------------------------------ labels

    public TruthSpec anomaly(String scenarioId, String issue, String outcome, String reason, String path, String reimbursement,
                             String severity, String explanation, String evidence, List<String> related, String rules) {
        return new TruthSpec(scenarioId, issue, true, outcome, reason, path, reimbursement, severity, explanation, evidence, related, rules);
    }

    public TruthSpec designed(String scenarioId, String issue, String outcome, String reason, String path, String reimbursement,
                              String explanation, String evidence, List<String> related, String rules) {
        return new TruthSpec(scenarioId, issue, false, outcome, reason, path, reimbursement, "NONE", explanation, evidence, related, rules);
    }

    public String expensePath(Employee e, BigDecimal usd, boolean clientEntertainment) {
        return ApprovalOracle.describe(ctx.oracle.resolve(e, ApprovalOracle.expenseRoles(usd, clientEntertainment)));
    }

    public int expenseLevels(Employee e, BigDecimal usd, boolean clientEntertainment) {
        return ctx.oracle.resolve(e, ApprovalOracle.expenseRoles(usd, clientEntertainment)).size();
    }

    public String leavePath(Employee e, int days) {
        return ApprovalOracle.describe(ctx.oracle.resolve(e, ApprovalOracle.leaveRoles(days)));
    }

    public Timeline.Result approveAll(Plan p, int levels) {
        Timeline.Result r = Timeline.script(Timeline.Script.APPROVE_ALL, levels, Timeline.evaluationTime(p.submitAt),
                new Random(rng.nextLong()), cutoff);
        p.decisions.addAll(r.decisions());
        return r;
    }

    public Timeline.Result script(Plan p, Timeline.Script script, int levels) {
        Timeline.Result r = Timeline.script(script, levels, Timeline.evaluationTime(p.submitAt), new Random(rng.nextLong()), cutoff);
        p.decisions.addAll(r.decisions());
        return r;
    }
}
