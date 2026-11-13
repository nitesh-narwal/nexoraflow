package me.niteshh.nexoraflow.generator.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.leave.LeaveSubmission;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.generator.plan.Plans.LeavePlan;
import me.niteshh.nexoraflow.generator.plan.Plans.SideAction;
import me.niteshh.nexoraflow.generator.plan.Plans.SideKind;
import me.niteshh.nexoraflow.generator.plan.Plans.TruthSpec;

/**
 * Plans the baseline leave workload. Every planned request is valid BY CONSTRUCTION: the planner tracks its own
 * "shadow ledger" of each balance (granted days minus days committed by earlier planned requests), keeps requests
 * of an employee from overlapping, respects notice and consecutive-day limits, and avoids weekends / holidays.
 * The expected outcome of each request is derived from this construction plus the scripted approver behaviour -
 * the decision engine is never consulted.
 */
@Component
public class LeavePlanner {

    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    /** One committed (reserved or consumed) block of days in the shadow ledger. */
    private record Commitment(BigDecimal days, Instant from, Instant to) {
        boolean activeAt(Instant t) {
            return !t.isBefore(from) && (to == null || t.isBefore(to));
        }
    }

    private static final class ShadowLedger {
        final LeavePolicy policy;
        final BigDecimal opening;
        final BigDecimal carried;
        final Employee employee;
        final int year;
        final List<Commitment> commitments = new ArrayList<>();

        ShadowLedger(LeavePolicy policy, Employee employee, PlanningContext.BalanceSeed seed, int year) {
            this.policy = policy;
            this.employee = employee;
            this.opening = seed == null ? BigDecimal.ZERO : seed.opening();
            this.carried = seed == null ? BigDecimal.ZERO : seed.carriedForward();
            this.year = year;
        }

        BigDecimal granted(Instant t) {
            BigDecimal g = opening.add(carried);
            if (policy.getAccrualFrequency() == AccrualFrequency.MONTHLY) {
                YearMonth first = firstAccrualMonth();
                YearMonth now = YearMonth.from(t.atZone(java.time.ZoneOffset.UTC));
                if (!now.isBefore(first)) {
                    int k = (int) ChronoUnit.MONTHS.between(first, now) + 1;
                    g = g.add(policy.getAnnualEntitlement().multiply(BigDecimal.valueOf(Math.min(12, k)))
                            .divide(TWELVE, 2, RoundingMode.HALF_UP));
                }
            }
            return g;
        }

        private YearMonth firstAccrualMonth() {
            LocalDate j = employee.getJoiningDate();
            if (j.getYear() < year) {
                return YearMonth.of(year, 1);
            }
            return j.getDayOfMonth() == 1 ? YearMonth.from(j) : YearMonth.from(j).plusMonths(1);
        }

        BigDecimal available(Instant t) {
            BigDecimal a = granted(t);
            for (Commitment c : commitments) {
                if (c.activeAt(t)) {
                    a = a.subtract(c.days());
                }
            }
            return a;
        }
    }

    public List<LeavePlan> planBaseline(PlanningContext ctx) {
        Random rng = ctx.seeds.random("leave|baseline");
        LocalDate refDate = ctx.settings.referenceDate();
        Instant cutoff = ctx.settings.cutoff();
        int year = ctx.settings.year();

        List<Employee> eligible = ctx.org.all().stream()
                .filter(e -> e.getEmploymentStatus() == EmploymentStatus.ACTIVE && !ctx.pool.isReserved(e.getEmployeeId()))
                .filter(e -> e.getEmploymentType() != EmploymentType.CONTRACTOR)
                .filter(e -> e.getJoiningDate().isBefore(refDate.minusDays(30)))
                .toList();
        int target = ctx.settings.profile().leaveRequests();
        int[] counts = new int[eligible.size()];
        // a few attempts fail the feasibility checks, so draw slightly more and trim back to the target
        int draws = (int) Math.ceil(target * 1.06);
        for (int i = 0; i < draws; i++) {
            counts[rng.nextInt(eligible.size())]++;
        }

        List<LeavePlan> plans = new ArrayList<>();
        for (int idx = 0; idx < eligible.size(); idx++) {
            Employee e = eligible.get(idx);
            if (counts[idx] == 0) {
                continue;
            }
            Set<LocalDate> holidays = ctx.holidays.getOrDefault(e.getCountry(), Set.of());
            LeavePolicy annual = ctx.leavePolicies.get("LPOL-ANNUAL-" + e.getCountry());
            LeavePolicy sick = ctx.leavePolicies.get("LPOL-SICK");
            ShadowLedger annualLedger = annual == null || !annual.eligibleTypes().contains(e.getEmploymentType()) ? null
                    : new ShadowLedger(annual, e, ctx.balanceSeeds.get(PlanningContext.balanceKey(e.getEmployeeId(), annual.getLeavePolicyId())), year);
            ShadowLedger sickLedger = !sick.eligibleTypes().contains(e.getEmploymentType()) ? null
                    : new ShadowLedger(sick, e, ctx.balanceSeeds.get(PlanningContext.balanceKey(e.getEmployeeId(), sick.getLeavePolicyId())), year);

            List<LocalDate[]> occupied = new ArrayList<>();
            LocalDate cursor = ctx.settings.simulationStart().isAfter(e.getJoiningDate().plusDays(14))
                    ? ctx.settings.simulationStart() : e.getJoiningDate().plusDays(14);
            for (int n = 0; n < counts[idx]; n++) {
                LeavePlan plan = null;
                for (int attempt = 0; attempt < 14 && plan == null; attempt++) {
                    plan = tryPlan(ctx, rng, e, holidays, annualLedger, sickLedger, occupied, cursor, refDate, cutoff);
                    if (plan != null) {
                        cursor = plan.submitAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
                    }
                }
                if (plan != null) {
                    plans.add(plan);
                }
            }
        }
        // Dropping plans never invalidates the others: removing a request only frees balance, it cannot create an overlap.
        if (plans.size() > target) {
            java.util.Collections.shuffle(plans, rng);
            List<LeavePlan> kept = new ArrayList<>(plans.subList(0, target));
            kept.sort(java.util.Comparator.comparing((LeavePlan p) -> p.key));
            return kept;
        }
        return plans;
    }

    private LeavePlan tryPlan(PlanningContext ctx, Random rng, Employee e, Set<LocalDate> holidays, ShadowLedger annualLedger,
                              ShadowLedger sickLedger, List<LocalDate[]> occupied, LocalDate cursor, LocalDate refDate, Instant cutoff) {
        // all draws first, so a failed attempt consumes a fixed number of random values
        boolean wantSick = rng.nextInt(100) < 18 || annualLedger == null;
        int gapDays = 4 + rng.nextInt(38);
        int noticeExtra = rng.nextInt(26);
        int durationRoll = rng.nextInt(100);
        int longRoll = rng.nextInt(100);
        int hour = 8 + rng.nextInt(9);
        int minute = rng.nextInt(60);
        int scriptRoll = rng.nextInt(100);
        int cancelRoll = rng.nextInt(100);
        int cancelDelayDays = 1 + rng.nextInt(5);
        Random scriptRng = new Random(rng.nextLong());

        ShadowLedger ledger = wantSick ? sickLedger : annualLedger;
        if (ledger == null) {
            return null;
        }
        LeavePolicy policy = ledger.policy;
        LocalDate submitDate = cursor.plusDays(gapDays);
        while (!Calendars.isWorkingDay(submitDate, holidays)) {
            submitDate = submitDate.plusDays(1);
        }
        if (!submitDate.isBefore(refDate)) {
            return null;
        }
        LocalDate start;
        int days;
        if (wantSick) {
            start = submitDate;
            days = durationRoll < 60 ? 1 : durationRoll < 90 ? 2 : 3;
        } else {
            start = Calendars.nextWorkingDay(submitDate.plusDays(policy.getMinimumNoticeDays() + noticeExtra), holidays);
            days = durationRoll < 40 ? 1 : durationRoll < 65 ? 2 : durationRoll < 80 ? 3 : durationRoll < 90 ? 4 : 5;
            if (longRoll < 6) {
                days = 11 + (durationRoll % 3);
            }
        }
        Instant submitAt = Timeline.at(submitDate, hour, minute);
        if (submitAt.isAfter(cutoff.minusSeconds(3600))) {
            return null;
        }
        Instant evaluatedAt = Timeline.evaluationTime(submitAt);

        int maxDays = Math.min(days, policy.getMaximumConsecutiveDays());
        BigDecimal available = ledger.available(evaluatedAt);
        while (maxDays > 0 && BigDecimal.valueOf(maxDays).compareTo(available) > 0) {
            maxDays--;
        }
        if (maxDays < 1) {
            return null;
        }
        days = maxDays;
        LocalDate end = Calendars.endAfterWorkingDays(start, days, holidays);
        if (end.getYear() != start.getYear() || end.getYear() != ctx.settings.year()) {
            return null;
        }
        for (LocalDate[] o : occupied) {
            if (!start.isAfter(o[1]) && !end.isBefore(o[0])) {
                return null;
            }
        }
        int businessDays = Calendars.businessDays(start, end, holidays);
        if (businessDays != days) {
            return null;
        }
        boolean needsDocument = wantSick && days > policy.getDocumentationThresholdDays();
        LeaveSubmission submission = new LeaveSubmission(e.getEmployeeId(), policy.getLeavePolicyId(), start, end, null,
                wantSick ? "Unwell" : "Planned time off", needsDocument ? "MED-CERT-" + ctx.seeds.seed() + "-" + e.getEmployeeId() + "-" + start : null);
        LeavePlan plan = new LeavePlan(ctx.nextLeaveKey(), e.getEmployeeId(), submitAt, submission);

        // expected route and scripted approver behaviour
        List<String> roles = ApprovalOracle.leaveRoles(days);
        List<ApprovalOracle.ExpectedStep> expected = ctx.oracle.resolve(e, roles);
        int levels = expected.size();
        Timeline.Script script = Timeline.baselineScript(scriptRoll, levels);
        Timeline.Result result = Timeline.script(script, levels, evaluatedAt, scriptRng, cutoff);
        plan.decisions.addAll(result.decisions());
        String finalStatus = result.finalStatus();
        Instant releaseAt = result.finalAt();
        BigDecimal daysDec = BigDecimal.valueOf(days);

        // cancellations
        Instant cancelAt = null;
        if (cancelRoll < 4) {
            Instant firstDecision = result.decisions().isEmpty() ? cutoff : result.decisions().get(0).at();
            Instant candidate = evaluatedAt.plusSeconds(Math.max(600, (firstDecision.getEpochSecond() - evaluatedAt.getEpochSecond()) / 2));
            if (candidate.isBefore(firstDecision) && candidate.isBefore(cutoff)) {
                cancelAt = candidate;
                plan.decisions.clear();
                finalStatus = "CANCELLED";
                releaseAt = cancelAt;
            }
        } else if (cancelRoll < 7 && finalStatus.equals("APPROVED")) {
            Instant candidate = releaseAt.plusSeconds(cancelDelayDays * 86400L);
            if (candidate.isBefore(cutoff) && candidate.isBefore(Timeline.at(start, 0, 0))) {
                cancelAt = candidate;
                finalStatus = "CANCELLED";
                releaseAt = cancelAt;
            }
        }
        if (cancelAt != null) {
            plan.sideActions.add(new SideAction(cancelAt, SideKind.CANCEL, e.getEmployeeId()));
        }
        // commitment of days in the shadow ledger
        boolean consumedForever = finalStatus.equals("APPROVED") || finalStatus.equals("PENDING_APPROVAL");
        ledger.commitments.add(new Commitment(daysDec, evaluatedAt, consumedForever ? null : releaseAt));
        occupied.add(new LocalDate[] {start, end});

        String reason = switch (finalStatus) {
            case "REJECTED" -> "REJECTED_BY_APPROVER";
            case "CANCELLED" -> "CANCELLED_BY_REQUESTER";
            default -> "";
        };
        plan.truth = new TruthSpec("BASE-" + plan.key, "NONE", false, finalStatus, reason, ApprovalOracle.describe(expected), "NONE",
                "NONE", "Valid baseline leave request (" + days + " business day(s), " + policy.getLeavePolicyId()
                + "); outcome follows the scripted approver behaviour", "NONE", List.of(), "LV-01..LV-11");
        return plan;
    }
}
