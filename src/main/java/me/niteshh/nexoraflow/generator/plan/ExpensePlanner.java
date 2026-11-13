package me.niteshh.nexoraflow.generator.plan;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.expense.ExpenseSubmission;
import me.niteshh.nexoraflow.generator.plan.ClaimFactory.LineDraft;
import me.niteshh.nexoraflow.generator.plan.Plans.ClaimPlan;
import me.niteshh.nexoraflow.generator.plan.Plans.SideAction;
import me.niteshh.nexoraflow.generator.plan.Plans.SideKind;
import me.niteshh.nexoraflow.generator.plan.Plans.TruthSpec;
import me.niteshh.nexoraflow.policy.ExpensePolicy;

/**
 * Plans the baseline expense workload. Every claim is valid BY CONSTRUCTION (limits, receipts, employment dates,
 * cost center, 70%-of-budget cap, no split/duplicate look-alikes). Expected outcomes come from construction, the
 * documented approval policy ({@link ApprovalOracle}) and the scripted approver behaviour - never from the engine.
 */
@Component
public class ExpensePlanner {

    enum Template { MEALS, TRIP, CLIENT, TRAINING, OFFICE, CONFERENCE }

    private static final Map<String, int[]> TEMPLATE_WEIGHTS = Map.of(
            // MEALS, TRIP, CLIENT, TRAINING, OFFICE, CONFERENCE
            "DEP-SAL", new int[] {30, 25, 25, 0, 10, 0},
            "DEP-MKT", new int[] {30, 15, 15, 10, 20, 5},
            "DEP-CS", new int[] {35, 20, 10, 10, 25, 0},
            "DEP-ENG", new int[] {35, 12, 0, 25, 25, 3},
            "DEP-PRD", new int[] {35, 15, 0, 20, 25, 5},
            "DEP-HR", new int[] {40, 5, 0, 20, 30, 5},
            "DEP-FIN", new int[] {40, 10, 0, 25, 20, 5},
            "DEP-OPS", new int[] {40, 20, 0, 10, 30, 0});
    private static final Map<String, Double> EMPLOYEE_WEIGHT = Map.of(
            "DEP-SAL", 1.6, "DEP-CS", 1.2, "DEP-MKT", 1.2, "DEP-ENG", 1.0, "DEP-OPS", 1.0, "DEP-PRD", 0.9, "DEP-FIN", 0.6, "DEP-HR", 0.5);

    public List<ClaimPlan> planBaseline(PlanningContext ctx) {
        Random rng = ctx.seeds.random("expense|baseline");
        ClaimFactory factory = new ClaimFactory(ctx);
        LocalDate refDate = ctx.settings.referenceDate();
        List<Employee> eligible = ctx.org.all().stream()
                .filter(e -> e.getEmploymentStatus() == EmploymentStatus.ACTIVE && !ctx.pool.isReserved(e.getEmployeeId()))
                .filter(e -> e.getJoiningDate().isBefore(refDate.minusDays(21)))
                .toList();
        double[] cumulative = new double[eligible.size()];
        double sum = 0;
        for (int i = 0; i < eligible.size(); i++) {
            Employee e = eligible.get(i);
            sum += EMPLOYEE_WEIGHT.getOrDefault(e.getDepartmentId(), 1.0) * (e.getJobLevel() <= 4 ? 1.3 : 1.0);
            cumulative[i] = sum;
        }
        List<ClaimPlan> plans = new ArrayList<>();
        int target = ctx.settings.profile().expenseClaims();
        int safety = target * 20;
        while (plans.size() < target && safety-- > 0) {
            double pick = rng.nextDouble() * sum;
            int idx = 0;
            while (idx < cumulative.length - 1 && cumulative[idx] < pick) {
                idx++;
            }
            ClaimPlan plan = tryClaim(ctx, factory, rng, eligible.get(idx));
            if (plan != null) {
                plans.add(plan);
            }
        }
        return plans;
    }

    private ClaimPlan tryClaim(PlanningContext ctx, ClaimFactory f, Random rng, Employee e) {
        LocalDate refDate = ctx.settings.referenceDate();
        Instant cutoff = ctx.settings.cutoff();
        LocalDate earliest = ctx.settings.simulationStart().plusDays(7).isAfter(e.getJoiningDate().plusDays(14))
                ? ctx.settings.simulationStart().plusDays(7) : e.getJoiningDate().plusDays(14);
        int span = (int) (refDate.toEpochDay() - 1 - earliest.toEpochDay());
        if (span <= 0) {
            return null;
        }
        LocalDate submitDate = earliest.plusDays(rng.nextInt(span));
        while (submitDate.getDayOfWeek() == DayOfWeek.SATURDAY || submitDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            submitDate = submitDate.plusDays(1);
        }
        if (!submitDate.isBefore(refDate)) {
            return null;
        }
        Instant submitAt = Timeline.at(submitDate, 8 + rng.nextInt(10), rng.nextInt(60));
        Template template = pickTemplate(e, rng);
        int scriptRoll = rng.nextInt(100);
        int payRoll = rng.nextInt(100);
        int payDelayDays = 2 + rng.nextInt(8);
        long scriptSeed = rng.nextLong();
        long drawSeed = rng.nextLong();
        Random draw = new Random(drawSeed);

        String currency = f.currencyOf(e);
        List<LineDraft> drafts = buildLines(template, e, submitDate, f, draw, currency);
        if (drafts.isEmpty() || drafts.size() > 10) {
            return null;
        }
        // validate every line against the documented policy and trackers
        BigDecimal totalLocal = BigDecimal.ZERO;
        boolean hasClientEntertainment = false;
        List<LineDraft> finalDrafts = new ArrayList<>();
        for (LineDraft d : drafts) {
            if (d.date().isBefore(e.getJoiningDate()) || d.date().isAfter(submitDate)) {
                return null;
            }
            Optional<ExpensePolicy> policy = f.policy(d.category(), e.getCountry(), e.getEmploymentType(), d.date());
            if (policy.isEmpty() || !f.fitsLimits(e, policy.get(), d.category(), d.date(), d.amountLocal())) {
                return null;
            }
            if (f.merchantConflict(e, d.merchant(), d.category(), d.date())) {
                return null;
            }
            totalLocal = totalLocal.add(d.amountLocal());
            hasClientEntertainment |= d.category().equals("CLIENT_ENTERTAINMENT");
            boolean mandatory = policy.get().isReceiptRequired() && d.amountLocal().compareTo(policy.get().getReceiptThreshold()) > 0;
            finalDrafts.add(new LineDraft(d.category(), d.merchant(), d.date(), d.description(), d.amountLocal(), mandatory || d.withReceipt()));
        }
        drafts = finalDrafts;
        // lines of this claim must also fit together (e.g. two meals on one day, two flights)
        if (!claimFitsTogether(e, drafts, f)) {
            return null;
        }
        Optional<String> projectCc = f.secondaryProjectCostCenter(e, submitDate);
        String costCenter = projectCc.isPresent() && rng.nextInt(100) < 10 ? projectCc.get()
                : f.primaryCostCenter(e, submitDate).orElse(null);
        if (costCenter == null || !ctx.costCenters.get(costCenter).isActive()) {
            return null;
        }
        BigDecimal totalUsd = f.toUsd(currency, totalLocal);
        if (!f.fitsBudget(costCenter, submitDate, totalUsd)) {
            return null;
        }

        // commit trackers
        for (LineDraft d : drafts) {
            f.addLimits(e, d.category(), d.date(), d.amountLocal());
            f.addMerchant(e, d.merchant(), d.category(), d.date());
        }
        f.addBudget(costCenter, submitDate, totalUsd);

        String key = ctx.nextClaimKey();
        List<ExpenseSubmission.Line> lines = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            lines.add(f.toLine(drafts.get(i), currency, key + "|" + (i + 1), draw));
        }
        LocalDate min = drafts.stream().map(LineDraft::date).min(LocalDate::compareTo).orElseThrow();
        LocalDate max = drafts.stream().map(LineDraft::date).max(LocalDate::compareTo).orElseThrow();
        ClaimPlan plan = new ClaimPlan(key, e.getEmployeeId(), submitAt,
                new ExpenseSubmission(e.getEmployeeId(), min, max, costCenter, currency, lines));

        // documented approval route and scripted approver behaviour
        List<ApprovalOracle.ExpectedStep> expected = ctx.oracle.resolve(e, ApprovalOracle.expenseRoles(totalUsd, hasClientEntertainment));
        int levels = expected.size();
        Instant evaluatedAt = Timeline.evaluationTime(submitAt);
        Timeline.Result result = Timeline.script(Timeline.baselineScript(scriptRoll, levels), levels, evaluatedAt, new Random(scriptSeed), cutoff);
        plan.decisions.addAll(result.decisions());
        String reimbursement = "NONE";
        if (result.finalStatus().equals("APPROVED")) {
            reimbursement = "PENDING_PAYMENT";
            Instant payAt = Timeline.at(result.finalAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusDays(payDelayDays), 10, 0);
            if (payRoll < 75 && payAt.isBefore(cutoff)) {
                plan.sideActions.add(new SideAction(payAt, SideKind.MARK_PAID, "PAYROLL"));
                reimbursement = "PAID";
            }
        }
        String reason = result.finalStatus().equals("REJECTED") ? "REJECTED_BY_APPROVER" : "";
        plan.truth = new TruthSpec("BASE-" + key, "NONE", false, result.finalStatus(), reason, ApprovalOracle.describe(expected),
                reimbursement, "NONE", "Valid baseline " + template + " claim of " + totalUsd + " USD; outcome follows the scripted approver behaviour",
                "NONE", List.of(), "EX-01..EX-19");
        return plan;
    }

    /** Lines of one claim must also fit the daily / monthly limits together with what is already committed. */
    private boolean claimFitsTogether(Employee e, List<LineDraft> drafts, ClaimFactory f) {
        Map<String, BigDecimal> daily = new java.util.HashMap<>();
        Map<String, BigDecimal> monthly = new java.util.HashMap<>();
        for (LineDraft d : drafts) {
            daily.merge(d.category() + "|" + d.date(), d.amountLocal(), BigDecimal::add);
            monthly.merge(d.category() + "|" + java.time.YearMonth.from(d.date()), d.amountLocal(), BigDecimal::add);
        }
        for (LineDraft d : drafts) {
            ExpensePolicy p = f.policy(d.category(), e.getCountry(), e.getEmploymentType(), d.date()).orElseThrow();
            BigDecimal day = daily.get(d.category() + "|" + d.date()).add(f.committed(e, d.category(), d.date(), true));
            BigDecimal month = monthly.get(d.category() + "|" + java.time.YearMonth.from(d.date())).add(f.committed(e, d.category(), d.date(), false));
            if (day.compareTo(p.getMaximumDailyAmount()) > 0 || month.compareTo(p.getMaximumMonthlyAmount()) > 0) {
                return false;
            }
        }
        return true;
    }

    private Template pickTemplate(Employee e, Random rng) {
        int[] w = TEMPLATE_WEIGHTS.get(e.getDepartmentId()).clone();
        if (e.getEmploymentType() != EmploymentType.FULL_TIME) {
            w[Template.CLIENT.ordinal()] = 0;
            w[Template.TRAINING.ordinal()] = 0;
            w[Template.CONFERENCE.ordinal()] = 0;
        }
        int total = 0;
        for (int x : w) {
            total += x;
        }
        int roll = rng.nextInt(total);
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0) {
                return Template.values()[i];
            }
        }
        return Template.MEALS;
    }

    // ------------------------------------------------------------------ templates

    private List<LineDraft> buildLines(Template t, Employee e, LocalDate submit, ClaimFactory f, Random r, String ccy) {
        List<LineDraft> out = new ArrayList<>();
        switch (t) {
            case MEALS -> {
                int n = 1 + r.nextInt(4);
                Set<LocalDate> used = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    LocalDate d = weekdayBefore(submit, 1 + r.nextInt(12));
                    if (!used.add(d)) {
                        continue;
                    }
                    out.add(line(f, "MEALS", d, usd(r, 9, 48), ccy, r, i));
                }
            }
            case TRIP -> addTrip(out, f, e, submit, r, ccy, 150, 1400, false);
            case CONFERENCE -> addTrip(out, f, e, submit, r, ccy, 400, 1700, true);
            case CLIENT -> {
                out.add(line(f, "CLIENT_ENTERTAINMENT", weekdayBefore(submit, 1 + r.nextInt(6)), usd(r, 100, 480), ccy, r, 0));
            }
            case TRAINING -> {
                LocalDate d = weekdayBefore(submit, 2 + r.nextInt(12));
                out.add(line(f, "TRAINING", d, usd(r, 200, 2600), ccy, r, 0));
                if (r.nextInt(100) < 30) {
                    out.add(line(f, "TRAVEL", d.plusDays(1).isAfter(submit) ? d : d.plusDays(1), usd(r, 120, 700), ccy, r, 1));
                }
            }
            case OFFICE -> {
                int n = 1 + r.nextInt(2);
                Set<LocalDate> used = new HashSet<>();
                for (int i = 0; i < n; i++) {
                    LocalDate d = weekdayBefore(submit, 1 + r.nextInt(14));
                    if (used.add(d)) {
                        out.add(line(f, "OFFICE_SUPPLIES", d, usd(r, 12, 300), ccy, r, i));
                    }
                }
            }
            default -> throw new IllegalStateException();
        }
        return out;
    }

    private void addTrip(List<LineDraft> out, ClaimFactory f, Employee e, LocalDate submit, Random r, String ccy, int flightLo, int flightHi,
                         boolean conference) {
        LocalDate start = weekdayBefore(submit, 4 + r.nextInt(10));
        int nights = 1 + r.nextInt(3);
        int i = 0;
        out.add(line(f, "TRAVEL", start, usd(r, flightLo, flightHi), ccy, r, i++));
        for (int k = 0; k < nights; k++) {
            out.add(line(f, "LODGING", start.plusDays(k), usd(r, 90, 270), ccy, r, i++));
        }
        int meals = 1 + r.nextInt(2);
        for (int k = 0; k < meals; k++) {
            out.add(line(f, "MEALS", start.plusDays(k), usd(r, 12, 40), ccy, r, i++));
        }
        out.add(line(f, "TRAVEL", start.plusDays(1), usd(r, 18, 75), ccy, r, i++));
        if (conference) {
            out.add(line(f, "TRAINING", start, usd(r, 1200, 2800), ccy, r, i));
        } else if (r.nextBoolean()) {
            out.add(line(f, "TRAVEL", start.plusDays(nights), usd(r, flightLo, flightHi), ccy, r, i));
        }
    }

    private LineDraft line(ClaimFactory f, String category, LocalDate date, double usd, String ccy, Random r, int n) {
        BigDecimal local = f.local(ccy, usd);
        String merchant = f.merchant(category, r);
        boolean optionalReceipt = r.nextInt(100) < 40; // mandatory receipts (above the policy threshold) are added during validation
        return new LineDraft(category, merchant, date, f.description(category, r), local, optionalReceipt);
    }

    private static double usd(Random r, int lo, int hi) {
        return lo + r.nextInt(hi - lo + 1);
    }

    private static LocalDate weekdayBefore(LocalDate date, int daysBack) {
        LocalDate d = date.minusDays(daysBack);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
