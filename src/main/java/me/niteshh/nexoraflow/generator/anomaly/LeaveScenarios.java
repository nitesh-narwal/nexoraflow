package me.niteshh.nexoraflow.generator.anomaly;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.generator.plan.Calendars;
import me.niteshh.nexoraflow.generator.plan.Plans.LeavePlan;
import me.niteshh.nexoraflow.generator.plan.Plans.SideAction;
import me.niteshh.nexoraflow.generator.plan.Plans.SideKind;
import me.niteshh.nexoraflow.generator.plan.Plans.TimedDecision;
import me.niteshh.nexoraflow.generator.plan.Plans.Verdict;
import me.niteshh.nexoraflow.generator.plan.PlanningContext;
import me.niteshh.nexoraflow.generator.plan.Timeline;
import me.niteshh.nexoraflow.policy.LeavePolicy;

/**
 * Deliberately designed leave anomalies. The expected outcome of every scenario follows from its construction and
 * the documented policy (balance arithmetic, notice, limits, calendars), never from running the engine.
 */
final class LeaveScenarios {

    private LeaveScenarios() {
    }

    static void plan(ScenarioKit k) {
        negativeBalance(k);
        overlappingLeave(k);
        cancelledLeave(k);
        invalidDateRange(k);
        inactiveEmployee(k);
        notEligible(k);
        insufficientNotice(k);
        maximumConsecutive(k);
        nonWorkingDays(k);
        documentationMissing(k);
        policyNotEffective(k);
        unknownEmployee(k);
    }

    private static LeavePolicy annual(ScenarioKit k, Employee e) {
        return k.ctx.leavePolicies.get("LPOL-ANNUAL-" + e.getCountry());
    }

    private static Set<LocalDate> holidays(ScenarioKit k, Employee e) {
        return k.ctx.holidays.getOrDefault(e.getCountry(), Set.of());
    }

    private static LocalDate endOf(ScenarioKit k, Employee e, LocalDate start, int days) {
        return Calendars.endAfterWorkingDays(start, days, holidays(k, e));
    }

    /** Scenario 1: negative / insufficient balance. A consumes most of the balance, B asks for more than is left. */
    private static void negativeBalance(ScenarioKit k) {
        int made = 0;
        Set<String> used = new java.util.HashSet<>(); // one balance per scenario: an employee is never reused here
        for (int i = 0; i < k.count(12) && made < k.count(12); i++) {
            Optional<Employee> pick = k.pool(x -> !x.getCountry().equals("IN") && annual(k, x) != null && !used.contains(x.getEmployeeId()));
            if (pick.isEmpty()) {
                return;
            }
            Employee e = pick.get();
            used.add(e.getEmployeeId());
            LeavePolicy policy = annual(k, e);
            PlanningContext.BalanceSeed seed = k.ctx.balanceSeeds.get(PlanningContext.balanceKey(e.getEmployeeId(), policy.getLeavePolicyId()));
            BigDecimal available = seed.opening().add(seed.carriedForward());
            int max = policy.getMaximumConsecutiveDays();
            int a = Math.min(max, available.intValue());
            BigDecimal remaining = available.subtract(BigDecimal.valueOf(a));
            if (a < 1 || remaining.compareTo(BigDecimal.valueOf(max)) >= 0) {
                continue;
            }
            int b = Math.min(max, remaining.intValue() + 2);
            Set<LocalDate> hol = holidays(k, e);
            LocalDate submitA = Calendars.nextWorkingDay(LocalDate.of(k.ctx.settings.year(), 1, 12).plusDays(made * 2L), hol);
            LocalDate startA = Calendars.nextWorkingDay(submitA.plusDays(policy.getMinimumNoticeDays() + 7), hol);
            LocalDate endA = Calendars.endAfterWorkingDays(startA, a, hol);
            LeavePlan planA = k.leave(e, policy.getLeavePolicyId(), startA, endA, k.submitTime(submitA), null);
            Timeline.Result ra = k.approveAll(planA, Math.max(1, new me.niteshh.nexoraflow.generator.plan.ApprovalOracle(k.ctx.org)
                    .resolve(e, me.niteshh.nexoraflow.generator.plan.ApprovalOracle.leaveRoles(a)).size()));

            LocalDate submitB = Calendars.nextWorkingDay(endA.plusDays(7), hol);
            LocalDate startB = Calendars.nextWorkingDay(submitB.plusDays(policy.getMinimumNoticeDays() + 7), hol);
            LocalDate endB = Calendars.endAfterWorkingDays(startB, b, hol);
            if (endB.getYear() != startB.getYear() || submitB.isAfter(k.ref.minusDays(1))) {
                k.plans.remove(planA);
                continue;
            }
            LeavePlan planB = k.leave(e, policy.getLeavePolicyId(), startB, endB, k.submitTime(submitB), null);
            k.markBalanceDepleted(e); // request A consumed most of the balance: other scenarios must not pick this employee
            String scenario = k.nextScenarioId("NEG-BALANCE");
            planA.truth = k.designed(scenario, "NONE", ra.finalStatus(), "", k.leavePath(e, a), "NONE",
                    "Setup: " + a + " business days approved, consuming most of the " + available + "-day balance", "NONE", List.of(), "LV-01..LV-11");
            planB.truth = k.anomaly(scenario, "NEGATIVE_BALANCE", "REJECTED", "INSUFFICIENT_BALANCE", null, "NONE", "MEDIUM",
                    "Requests " + b + " business days but only " + remaining + " remain after the approved leave consumed " + a,
                    "DECISION_REASON", List.of(planA.key), "LV-08");
            made++;
        }
    }

    /** Scenario 2: overlapping approved leave. Variant (a): B submitted after A is approved. Variant (b): race - both pending. */
    private static void overlappingLeave(ScenarioKit k) {
        int total = k.count(10);
        for (int i = 0; i < total; i++) {
            boolean race = i % 5 >= 3; // 40% race variants
            Optional<Employee> pick = k.pool(x -> annual(k, x) != null);
            if (pick.isEmpty()) {
                return;
            }
            Employee e = pick.get();
            LeavePolicy policy = annual(k, e);
            Set<LocalDate> hol = holidays(k, e);
            LocalDate startA = Calendars.nextWorkingDay(k.ref.plusDays(26 + i * 6L), hol);
            LocalDate endA = Calendars.endAfterWorkingDays(startA, 3, hol);
            LocalDate startB = Calendars.nextWorkingDay(startA.plusDays(1), hol);
            LocalDate endB = Calendars.endAfterWorkingDays(startB, 3, hol);
            if (endB.getYear() != k.ctx.settings.year()) {
                continue;
            }
            Instant submitA = k.submitTime(k.dayBeforeRef(14 - (i % 5), 9));
            LeavePlan a = k.leave(e, policy.getLeavePolicyId(), startA, endA, submitA, null);
            Instant evalA = Timeline.evaluationTime(submitA);
            Instant approveA = evalA.plusSeconds(4 * 3600);
            a.decisions.add(new TimedDecision(approveA, Verdict.APPROVE));
            LeavePlan b;
            String scenario = k.nextScenarioId("OVERLAP");
            if (race) {
                Instant submitB = submitA.plusSeconds(1800);
                b = k.leave(e, policy.getLeavePolicyId(), startB, endB, submitB, null);
                b.decisions.add(new TimedDecision(Timeline.evaluationTime(submitB).plusSeconds(12 * 3600), Verdict.APPROVE));
                b.truth = k.anomaly(scenario, "OVERLAPPING_APPROVED_LEAVE", "FLAGGED", "OVERLAPPING_APPROVED_LEAVE", k.leavePath(e, 3), "NONE",
                        "HIGH", "Both requests were pending; when B reached final approval it overlapped the already approved request A",
                        "DECISION_REASON", List.of(a.key), "LV-09");
            } else {
                Instant submitB = approveA.plusSeconds(86400);
                b = k.leave(e, policy.getLeavePolicyId(), startB, endB, submitB, null);
                b.truth = k.anomaly(scenario, "OVERLAPPING_APPROVED_LEAVE", "FLAGGED", "OVERLAPPING_APPROVED_LEAVE", null, "NONE", "HIGH",
                        "B overlaps leave that was already approved (A); both records are preserved and B is flagged",
                        "DECISION_REASON", List.of(a.key), "LV-09");
            }
            a.truth = k.designed(scenario, "NONE", "APPROVED", "", k.leavePath(e, 3), "NONE",
                    "Approved leave that the overlapping request B collides with (record preserved untouched)", "NONE", List.of(), "LV-01..LV-11");
        }
    }

    /** Cancelled leave: approved, then cancelled before it starts - the days must return to the balance. */
    private static void cancelledLeave(ScenarioKit k) {
        for (int i = 0; i < k.count(6); i++) {
            Optional<Employee> pick = k.pool(x -> annual(k, x) != null);
            if (pick.isEmpty()) {
                return;
            }
            Employee e = pick.get();
            LeavePolicy policy = annual(k, e);
            Set<LocalDate> hol = holidays(k, e);
            LocalDate start = Calendars.nextWorkingDay(k.ref.plusDays(32 + i * 5L), hol);
            LocalDate end = Calendars.endAfterWorkingDays(start, 2, hol);
            if (end.getYear() != k.ctx.settings.year()) {
                continue;
            }
            LeavePlan p = k.leave(e, policy.getLeavePolicyId(), start, end, k.submitTime(k.dayBeforeRef(24, 18)), null);
            Timeline.Result r = k.approveAll(p, 1);
            if (r.finalAt() == null) {
                k.plans.remove(p);
                continue;
            }
            p.sideActions.add(new SideAction(r.finalAt().plusSeconds(3 * 86400L), SideKind.CANCEL, e.getEmployeeId()));
            p.truth = k.designed(k.nextScenarioId("CANCELLED"), "CANCELLED_LEAVE", "CANCELLED", "CANCELLED_BY_REQUESTER", k.leavePath(e, 2), "NONE",
                    "Approved leave cancelled by the requester before it started: the consumed days are restored exactly once",
                    "BALANCE_LEDGER", List.of(), "LV-LIFECYCLE");
        }
    }

    private static void invalidDateRange(ScenarioKit k) {
        for (int i = 0; i < k.count(6); i++) {
            Employee e = k.anyPool();
            LocalDate start = Calendars.nextWorkingDay(k.ref.plusDays(30 + i * 3L), holidays(k, e));
            LeavePlan p = k.leave(e, annual(k, e).getLeavePolicyId(), start, start.minusDays(3), k.submitTime(k.dayBeforeRef(10, 2)), null);
            p.truth = k.anomaly(k.nextScenarioId("INVALID-RANGE"), "INVALID_DATE_RANGE", "REJECTED", "INVALID_DATE_RANGE", null, "NONE", "LOW",
                    "End date is before the start date; the submission is preserved as a rejected request", "DECISION_REASON", List.of(), "LV-04");
        }
    }

    private static void inactiveEmployee(ScenarioKit k) {
        List<Employee> inactive = k.employeesWithStatus(EmploymentStatus.INACTIVE);
        for (int i = 0; i < k.count(6) && !inactive.isEmpty(); i++) {
            Employee e = inactive.get(i % inactive.size());
            LeavePolicy policy = annual(k, e);
            if (policy == null) {
                continue;
            }
            LocalDate start = Calendars.nextWorkingDay(k.ref.plusDays(35 + i * 4L), holidays(k, e));
            LeavePlan p = k.leave(e, policy.getLeavePolicyId(), start, endOf(k, e, start, 2), k.submitTime(k.dayBeforeRef(12, 3)), null);
            p.truth = k.anomaly(k.nextScenarioId("INACTIVE-EMP"), "INACTIVE_EMPLOYEE", "REJECTED", "INACTIVE_EMPLOYEE", null, "NONE", "MEDIUM",
                    "The employee's status is INACTIVE, so no leave can be approved", "DECISION_REASON", List.of(), "LV-01");
        }
    }

    private static void notEligible(ScenarioKit k) {
        List<Employee> contractors = k.contractors();
        for (int i = 0; i < k.count(4) && !contractors.isEmpty(); i++) {
            Employee e = contractors.get(i % contractors.size());
            LeavePolicy policy = annual(k, e);
            LocalDate start = Calendars.nextWorkingDay(k.ref.plusDays(40 + i * 4L), holidays(k, e));
            LeavePlan p = k.leave(e, policy.getLeavePolicyId(), start, endOf(k, e, start, 2), k.submitTime(k.dayBeforeRef(12, 3)), null);
            p.truth = k.anomaly(k.nextScenarioId("NOT-ELIGIBLE"), "EMPLOYEE_NOT_ELIGIBLE", "REJECTED", "EMPLOYEE_NOT_ELIGIBLE", null, "NONE", "LOW",
                    "Contractors are not eligible for the annual leave policy (no policy assignment exists)", "DECISION_REASON", List.of(), "LV-03");
        }
    }

    private static void insufficientNotice(ScenarioKit k) {
        for (int i = 0; i < k.count(6); i++) {
            Employee e = k.anyPool();
            LeavePolicy policy = annual(k, e);
            LocalDate submit = k.dayBeforeRef(6, 3);
            LocalDate start = Calendars.nextWorkingDay(submit.plusDays(1), holidays(k, e));
            if (java.time.temporal.ChronoUnit.DAYS.between(submit, start) >= policy.getMinimumNoticeDays()) {
                continue;
            }
            LeavePlan p = k.leave(e, policy.getLeavePolicyId(), start, start, k.submitTime(submit), null);
            p.truth = k.anomaly(k.nextScenarioId("NOTICE"), "INSUFFICIENT_NOTICE", "REJECTED", "INSUFFICIENT_NOTICE", null, "NONE", "LOW",
                    "Only " + java.time.temporal.ChronoUnit.DAYS.between(submit, start) + " day(s) notice against a required "
                            + policy.getMinimumNoticeDays(), "DECISION_REASON", List.of(), "LV-06");
        }
    }

    private static void maximumConsecutive(ScenarioKit k) {
        int made = 0;
        for (int i = 0; i < k.count(5) * 3 && made < k.count(5); i++) {
            Optional<Employee> pick = k.pool(x -> annual(k, x) != null && !x.getCountry().equals("IN"));
            if (pick.isEmpty()) {
                return;
            }
            Employee e = pick.get();
            LeavePolicy policy = annual(k, e);
            PlanningContext.BalanceSeed seed = k.ctx.balanceSeeds.get(PlanningContext.balanceKey(e.getEmployeeId(), policy.getLeavePolicyId()));
            int days = policy.getMaximumConsecutiveDays() + 2;
            if (seed.opening().add(seed.carriedForward()).compareTo(BigDecimal.valueOf(days)) < 0) {
                continue;
            }
            LocalDate start = Calendars.nextWorkingDay(k.ref.plusDays(14 + made * 2L), holidays(k, e));
            LocalDate end = endOf(k, e, start, days);
            if (end.getYear() != k.ctx.settings.year()) {
                continue;
            }
            LeavePlan p = k.leave(e, policy.getLeavePolicyId(), start, end, k.submitTime(k.dayBeforeRef(10, 3)), null);
            p.truth = k.anomaly(k.nextScenarioId("MAX-CONSECUTIVE"), "MAX_CONSECUTIVE_DAYS_EXCEEDED", "REJECTED", "POLICY_LIMIT_EXCEEDED", null, "NONE",
                    "LOW", days + " business days requested against a maximum of " + policy.getMaximumConsecutiveDays(), "DECISION_REASON",
                    List.of(), "LV-07");
            made++;
        }
    }

    private static void nonWorkingDays(ScenarioKit k) {
        int total = k.count(5);
        for (int i = 0; i < total; i++) {
            Employee e = k.anyPool();
            LeavePolicy policy = annual(k, e);
            LocalDate start;
            LocalDate end;
            String what;
            if (i % 5 < 3) {
                LocalDate saturday = k.ref.plusDays(30 + i * 7L);
                while (saturday.getDayOfWeek() != java.time.DayOfWeek.SATURDAY) {
                    saturday = saturday.plusDays(1);
                }
                start = saturday;
                end = saturday.plusDays(1);
                what = "a weekend";
            } else {
                start = LocalDate.of(k.ctx.settings.year(), 12, 25);
                end = start;
                what = "a public holiday (Christmas Day)";
            }
            LeavePlan p = k.leave(e, policy.getLeavePolicyId(), start, end, k.submitTime(k.dayBeforeRef(10, 3)), null);
            p.truth = k.anomaly(k.nextScenarioId("NON-WORKING"), "HOLIDAY_OR_NON_WORKING_DAY_ONLY", "REJECTED", "HOLIDAY_OR_NON_WORKING_DAY_ONLY", null,
                    "NONE", "LOW", "The requested range covers only " + what + ": zero business days", "DECISION_REASON", List.of(), "LV-05");
        }
    }

    private static void documentationMissing(ScenarioKit k) {
        for (int i = 0; i < k.count(5); i++) {
            Employee e = k.anyPool();
            Set<LocalDate> hol = holidays(k, e);
            LocalDate start = Calendars.nextWorkingDay(k.dayBeforeRef(16 + i, 10), hol);
            LocalDate end = endOf(k, e, start, 4);
            Instant submitAt = Timeline.at(start, 9, 30);
            LeavePlan p = k.leave(e, "LPOL-SICK", start, end, submitAt, null);
            p.truth = k.anomaly(k.nextScenarioId("DOC-MISSING"), "DOCUMENTATION_MISSING", "NEEDS_REVIEW", "DOCUMENTATION_MISSING", null, "NONE",
                    "LOW", "Four sick days exceed the two-day threshold and no medical certificate was provided", "DECISION_REASON", List.of(), "LV-10");
        }
    }

    private static void policyNotEffective(ScenarioKit k) {
        List<Employee> singapore = k.filter(e -> e.getCountry().equals("SG") && e.getJobLevel() == 5
                && e.getEmploymentStatus() == EmploymentStatus.ACTIVE && e.getEmploymentType() == me.niteshh.nexoraflow.employee.EmploymentType.FULL_TIME
                && !k.ctx.pool.isReserved(e.getEmployeeId()) || (e.getCountry().equals("SG") && k.ctx.pool.members().contains(e)));
        for (int i = 0; i < k.count(3) && !singapore.isEmpty(); i++) {
            Employee e = singapore.get(i % singapore.size());
            LocalDate start = LocalDate.of(k.ctx.settings.year() + 1, 1, 11 + i);
            start = Calendars.nextWorkingDay(start, holidays(k, e));
            LeavePlan p = k.leave(e, "LPOL-ANNUAL-SG", start, start, k.submitTime(k.dayBeforeRef(9, 2)), null);
            p.truth = k.anomaly(k.nextScenarioId("POLICY-DATE"), "POLICY_EFFECTIVE_DATE_MISMATCH", "REJECTED", "POLICY_NOT_EFFECTIVE", null, "NONE",
                    "MEDIUM", "The Singapore annual leave policy is effective only until 2026-12-31; the request is dated in the following year",
                    "DECISION_REASON", List.of(), "LV-02");
        }
    }

    private static void unknownEmployee(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            LocalDate start = k.weekday(k.ref.plusDays(30 + i));
            String ghost = "EMP-" + String.format("%06d", 900_000 + i);
            LeavePlan p = new LeavePlan(k.ctx.nextLeaveKey(), ghost, k.submitTime(k.dayBeforeRef(8, 2)),
                    new me.niteshh.nexoraflow.leave.LeaveSubmission(ghost, "LPOL-ANNUAL-US", start, start.plusDays(1), null, "Ghost request", null));
            k.plans.add(p);
            p.truth = k.anomaly(k.nextScenarioId("UNKNOWN-EMP"), "INVALID_EMPLOYEE_REFERENCE", "REJECTED_AT_INTAKE", "EMPLOYEE_NOT_FOUND", null, "NONE",
                    "MEDIUM", "The submission references an employee that does not exist; it is preserved in staging, never as a canonical request",
                    "INTAKE", List.of(), "INTAKE");
        }
    }
}
