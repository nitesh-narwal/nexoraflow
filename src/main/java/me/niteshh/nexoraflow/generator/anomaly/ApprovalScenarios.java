package me.niteshh.nexoraflow.generator.anomaly;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.generator.plan.Calendars;
import me.niteshh.nexoraflow.generator.plan.Plans.ClaimPlan;
import me.niteshh.nexoraflow.generator.plan.Plans.LeavePlan;
import me.niteshh.nexoraflow.generator.plan.Plans.SideAction;
import me.niteshh.nexoraflow.generator.plan.Plans.SideKind;
import me.niteshh.nexoraflow.generator.plan.Timeline;
import me.niteshh.nexoraflow.policy.ExpensePolicy;

/**
 * Approval-workflow and reimbursement scenarios: self-approval and unauthorised approval attempts, unavailable
 * managers, an impossible hierarchy, duplicate reimbursement attempts and reimbursement before approval.
 */
final class ApprovalScenarios {

    private ApprovalScenarios() {
    }

    static void plan(ScenarioKit k) {
        selfApproval(k);
        unauthorizedApprover(k);
        managerUnavailable(k);
        invalidHierarchy(k);
        duplicateReimbursement(k);
        reimbursementBeforeApproval(k);
        completedStepOverwrite(k);
        outOfOrderApproval(k);
    }

    private static Set<LocalDate> holidays(ScenarioKit k, Employee e) {
        return k.ctx.holidays.getOrDefault(e.getCountry(), Set.of());
    }

    private static ClaimPlan smallClaim(ScenarioKit k, Employee e, LocalDate day, String seed, String merchant) {
        ExpensePolicy p = k.factory.policy("MEALS", e.getCountry(), e.getEmploymentType(), day).orElseThrow();
        BigDecimal amount = p.getMaximumPerTransaction().multiply(BigDecimal.valueOf(0.4)).setScale(2, RoundingMode.HALF_UP);
        return k.claim(e, k.factory.primaryCostCenter(e, day).orElseThrow(), Timeline.at(day, 9 + (seed.hashCode() & 7), 15),
                k.lineLocal("MEALS", merchant + " " + (Math.abs(seed.hashCode()) % 900 + 100), day, amount, k.factory.currencyOf(e),
                        k.factory.receipt(seed, k.rng), null));
    }

    private static LeavePlan smallLeave(ScenarioKit k, Employee e, int offset) {
        me.niteshh.nexoraflow.policy.LeavePolicy policy = k.ctx.leavePolicies.get("LPOL-ANNUAL-" + e.getCountry());
        Set<LocalDate> hol = holidays(k, e);
        // dates stay inside the dataset year and distinct per scenario instance
        LocalDate start = Calendars.nextWorkingDay(k.ref.plusDays(20 + (offset % 7) * 5L), hol);
        LocalDate end = Calendars.endAfterWorkingDays(start, 2, hol);
        if (end.getYear() != k.ctx.settings.year()) {
            start = Calendars.nextWorkingDay(LocalDate.of(k.ctx.settings.year(), 11, 2 + (offset % 7) * 2), hol);
            end = Calendars.endAfterWorkingDays(start, 2, hol);
        }
        return k.leave(e, policy.getLeavePolicyId(), start, end, k.submitTime(k.dayBeforeRef(9, 3)), null);
    }

    /** Scenario 6: the requester tries to approve their own request; the real approver approves later. */
    private static void selfApproval(ScenarioKit k) {
        int each = Math.max(1, k.count(10) / 2);
        for (int i = 0; i < each; i++) {
            Employee e = k.anyPool();
            LeavePlan lp = smallLeave(k, e, i);
            Instant eval = Timeline.evaluationTime(lp.submitAt);
            lp.sideActions.add(new SideAction(eval.plusSeconds(1500), SideKind.SELF_APPROVE_ATTEMPT, e.getEmployeeId()));
            Timeline.Result lr = k.approveAll(lp, 1);
            lp.truth = k.anomaly(k.nextScenarioId("SELF-APPROVAL"), "SELF_APPROVAL_ATTEMPT", lr.finalStatus(), "SELF_APPROVAL_NOT_ALLOWED",
                    k.leavePath(e, 2), "NONE", "HIGH", "The requester tried to approve their own leave request; the attempt is rejected and audited and the "
                            + "request stays in its workflow state until the real approver acts", "AUDIT_EVENT", List.of(), "AP-SELF");

            Employee c = k.anyPool();
            LocalDate day = k.dayBeforeRef(14, 6);
            ClaimPlan cp = smallClaim(k, c, day, "self-" + i, "Cafe Roma");
            cp.sideActions.add(new SideAction(Timeline.evaluationTime(cp.submitAt).plusSeconds(1500), SideKind.SELF_APPROVE_ATTEMPT, c.getEmployeeId()));
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(c), cp.submission.lines().get(0).amount());
            Timeline.Result cr = k.approveAll(cp, k.expenseLevels(c, usd, false));
            cp.truth = k.anomaly(k.nextScenarioId("SELF-APPROVAL"), "SELF_APPROVAL_ATTEMPT", cr.finalStatus(), "SELF_APPROVAL_NOT_ALLOWED",
                    k.expensePath(c, usd, false), cr.finalStatus().equals("APPROVED") ? "PENDING_PAYMENT" : "NONE", "HIGH",
                    "The requester tried to approve their own expense claim; the attempt is rejected and audited", "AUDIT_EVENT", List.of(), "AP-SELF");
        }
    }

    private static void unauthorizedApprover(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            Employee e = k.anyPool();
            Employee outsider = k.ctx.org.all().stream().filter(x -> x.getJobLevel() == 5 && !x.getDepartmentId().equals(e.getDepartmentId())
                    && x.getEmploymentStatus() == me.niteshh.nexoraflow.employee.EmploymentStatus.ACTIVE).skip(i).findFirst().orElse(null);
            if (outsider == null) {
                continue;
            }
            LocalDate day = k.dayBeforeRef(14, 6);
            ClaimPlan cp = smallClaim(k, e, day, "unauth-" + i, "Noodle Bar");
            cp.sideActions.add(new SideAction(Timeline.evaluationTime(cp.submitAt).plusSeconds(1500), SideKind.UNAUTHORIZED_APPROVE_ATTEMPT, outsider.getEmployeeId()));
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(e), cp.submission.lines().get(0).amount());
            Timeline.Result r = k.approveAll(cp, k.expenseLevels(e, usd, false));
            cp.truth = k.anomaly(k.nextScenarioId("UNAUTH-APPROVER"), "UNAUTHORIZED_APPROVAL_ATTEMPT", r.finalStatus(), "UNAUTHORIZED_APPROVER",
                    k.expensePath(e, usd, false), r.finalStatus().equals("APPROVED") ? "PENDING_PAYMENT" : "NONE", "HIGH",
                    "An employee who is neither the requester nor the assigned approver tried to approve; the attempt is rejected and audited",
                    "AUDIT_EVENT", List.of(), "AP-AUTH");
        }
    }

    /** Employees whose manager is inactive: the approval escalates to the next active manager. */
    private static void managerUnavailable(ScenarioKit k) {
        List<Employee> orphans = k.ctx.pool.orphans(k.ctx.org.all());
        for (int i = 0; i < k.count(8) && !orphans.isEmpty(); i++) {
            Employee e = orphans.get(i % orphans.size());
            if (i % 2 == 0) {
                LocalDate day = k.dayBeforeRef(14, 5);
                if (k.factory.primaryCostCenter(e, day).isEmpty()) {
                    continue;
                }
                ClaimPlan cp = smallClaim(k, e, day, "orphan-" + i, "Taco Lane");
                BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(e), cp.submission.lines().get(0).amount());
                Timeline.Result r = k.approveAll(cp, k.expenseLevels(e, usd, false));
                cp.truth = k.anomaly(k.nextScenarioId("MGR-UNAVAILABLE"), "MANAGER_UNAVAILABLE", r.finalStatus(), "ESCALATED_UNAVAILABLE_APPROVER",
                        k.expensePath(e, usd, false), r.finalStatus().equals("APPROVED") ? "PENDING_PAYMENT" : "NONE", "LOW",
                        "The direct manager is INACTIVE; approval is escalated to the next active manager in the reporting line",
                        "AUDIT_EVENT", List.of(), "AP-ESCALATION");
            } else {
                LeavePlan lp = smallLeave(k, e, i);
                Timeline.Result r = k.approveAll(lp, 1);
                lp.truth = k.anomaly(k.nextScenarioId("MGR-UNAVAILABLE"), "MANAGER_UNAVAILABLE", r.finalStatus(), "ESCALATED_UNAVAILABLE_APPROVER",
                        k.leavePath(e, 2), "NONE", "LOW", "The direct manager is INACTIVE; the leave approval is escalated up the reporting line",
                        "AUDIT_EVENT", List.of(), "AP-ESCALATION");
            }
        }
    }

    /** The CEO has no manager at all: no approval path can be built, so the request goes to review. */
    private static void invalidHierarchy(ScenarioKit k) {
        Employee ceo = k.ctx.org.ceo();
        LocalDate day = k.dayBeforeRef(12, 5);
        ClaimPlan cp = smallClaim(k, ceo, day, "ceo-claim", "The Capital Grill");
        cp.truth = k.anomaly(k.nextScenarioId("HIERARCHY"), "INVALID_APPROVAL_HIERARCHY", "NEEDS_REVIEW", "NO_VALID_APPROVER", null, "NONE", "HIGH",
                "The CEO has no manager, so no approver can be determined; the claim is sent to review instead of being auto-approved",
                "DECISION_REASON", List.of(), "EX-19");
        LeavePlan lp = smallLeave(k, ceo, 0);
        lp.truth = k.anomaly(k.nextScenarioId("HIERARCHY"), "INVALID_APPROVAL_HIERARCHY", "NEEDS_REVIEW", "NO_VALID_APPROVER", null, "NONE", "HIGH",
                "The CEO has no manager, so no approver can be determined for the leave request", "DECISION_REASON", List.of(), "LV-11");
    }

    /** Scenario 7: a payroll run asks for the reimbursement of an already reimbursed claim. */
    private static void duplicateReimbursement(ScenarioKit k) {
        for (int i = 0; i < k.count(8); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(28, 18);
            ClaimPlan cp = smallClaim(k, e, day, "dup-rmb-" + i, "Green Fork");
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(e), cp.submission.lines().get(0).amount());
            Timeline.Result r = k.approveAll(cp, k.expenseLevels(e, usd, false));
            if (!r.finalStatus().equals("APPROVED")) {
                k.plans.remove(cp);
                continue;
            }
            Instant retry = r.finalAt().plusSeconds(86400);
            cp.sideActions.add(new SideAction(retry, SideKind.REIMBURSE_RETRY, "PAYROLL-RUN"));
            if (i % 2 == 0) {
                cp.sideActions.add(new SideAction(retry.plusSeconds(86400), SideKind.REIMBURSE_RETRY, "PAYROLL-RUN"));
            }
            cp.truth = k.anomaly(k.nextScenarioId("DUP-REIMBURSEMENT"), "DUPLICATE_REIMBURSEMENT_ATTEMPT", "APPROVED", "DUPLICATE_REIMBURSEMENT",
                    k.expensePath(e, usd, false), "PENDING_PAYMENT", "HIGH",
                    "The first reimbursement was created at final approval; later attempts to reimburse the same claim are rejected and recorded, "
                            + "so exactly one reimbursement exists", "REIMBURSEMENT_ATTEMPT", List.of(), "RB-DUP");
        }
    }

    /** An approver tries to approve a step that is already complete: completed decisions cannot be overwritten. */
    private static void completedStepOverwrite(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(24, 14);
            ClaimPlan cp = smallClaim(k, e, day, "overwrite-" + i, "Pho Corner");
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(e), cp.submission.lines().get(0).amount());
            Timeline.Result r = k.approveAll(cp, k.expenseLevels(e, usd, false));
            if (r.finalAt() == null) {
                k.plans.remove(cp);
                continue;
            }
            cp.sideActions.add(new SideAction(r.finalAt().plusSeconds(7200), SideKind.REPEAT_APPROVE, "APPROVER"));
            cp.truth = k.anomaly(k.nextScenarioId("OVERWRITE"), "COMPLETED_APPROVAL_OVERWRITE_ATTEMPT", r.finalStatus(), "STEP_ALREADY_COMPLETED",
                    k.expensePath(e, usd, false), r.finalStatus().equals("APPROVED") ? "PENDING_PAYMENT" : "NONE", "MEDIUM",
                    "An approver tried to approve a step that was already approved; completed approvals are immutable and the attempt is recorded",
                    "AUDIT_EVENT", List.of(), "AP-IMMUTABLE");
        }
    }

    /** Level-2 approver acts while level 1 is still open: steps must run in the configured order. */
    private static void outOfOrderApproval(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            // not a Finance employee: otherwise the Finance reviewer could be the requester's own manager and collapse into one step
            LocalDate day = k.dayBeforeRef(110, 80);
            Employee e = k.bigTicketSubject(x -> !x.getCountry().equals("IN") && !x.getDepartmentId().equals("DEP-FIN"), day.minusDays(5), day, "TRAVEL").orElse(null);
            if (e == null) {
                return;
            }
            String cc = k.scenarioCostCenter(e).orElseGet(() -> k.factory.primaryCostCenter(e, day).orElseThrow());
            ClaimPlan cp = k.claim(e, cc, Timeline.at(day, 13, 20),
                    k.line(e, "TRAVEL", "Aero Nimbus " + i, day.minusDays(5), 2200, true, "ooo-a-" + i),
                    k.line(e, "TRAVEL", "BlueJet " + i, day.minusDays(1), 2200, true, "ooo-b-" + i));
            BigDecimal usd = BigDecimal.ZERO;
            for (var l : cp.submission.lines()) {
                usd = usd.add(l.amount());
            }
            usd = k.factory.toUsd(k.factory.currencyOf(e), usd);
            Timeline.Result r = k.approveAll(cp, k.expenseLevels(e, usd, false));
            cp.sideActions.add(new SideAction(Timeline.evaluationTime(cp.submitAt).plusSeconds(1800), SideKind.OUT_OF_ORDER_APPROVE, "APPROVER"));
            cp.truth = k.anomaly(k.nextScenarioId("OUT-OF-ORDER"), "OUT_OF_ORDER_APPROVAL_ATTEMPT", r.finalStatus(), "INVALID_STEP_ORDER",
                    k.expensePath(e, usd, false), r.finalStatus().equals("APPROVED") ? "PENDING_PAYMENT" : "NONE", "MEDIUM",
                    "The level-2 approver tried to approve before level 1 completed; approval steps must follow the configured order",
                    "AUDIT_EVENT", List.of(), "AP-ORDER");
        }
    }

    private static void reimbursementBeforeApproval(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(7, 3);
            ClaimPlan cp = smallClaim(k, e, day, "early-rmb-" + i, "Sakura Kitchen");
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(e), cp.submission.lines().get(0).amount());
            cp.sideActions.add(new SideAction(Timeline.evaluationTime(cp.submitAt).plusSeconds(3600), SideKind.REIMBURSE_RETRY, "PAYROLL-RUN"));
            cp.truth = k.anomaly(k.nextScenarioId("EARLY-REIMBURSEMENT"), "REIMBURSEMENT_BEFORE_APPROVAL", "PENDING_APPROVAL", "CLAIM_NOT_REIMBURSABLE",
                    k.expensePath(e, usd, false), "NONE", "HIGH",
                    "A reimbursement was requested while the claim is still awaiting approval; the system refuses and records the attempt",
                    "REIMBURSEMENT_ATTEMPT", List.of(), "RB-ELIGIBLE");
        }
    }
}
