package me.niteshh.nexoraflow.generator.anomaly;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.niteshh.nexoraflow.costcenter.CostCenter;
import me.niteshh.nexoraflow.costcenter.CostCenterStatus;
import me.niteshh.nexoraflow.employee.AssignmentType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.expense.ExpenseSubmission;
import me.niteshh.nexoraflow.generator.plan.Plans.ClaimPlan;
import me.niteshh.nexoraflow.generator.plan.Timeline;
import me.niteshh.nexoraflow.policy.ExpensePolicy;

/**
 * Deliberately designed expense anomalies. Expected outcomes follow from the scenario construction and the
 * documented policy only. Big-ticket scenarios are booked to each subject's dedicated "special project" cost center
 * in months other than the budget-exhaustion month so they never disturb baseline budgets.
 */
final class ExpenseScenarios {

    private ExpenseScenarios() {
    }

    static void plan(ScenarioKit k) {
        outsideEmploymentPeriod(k);
        splitClaims(k);
        missingCostCenter(k);
        missingReceipt(k);
        duplicateReceipt(k);
        invalidDateRange(k);
        inactiveEmployee(k);
        inactiveApprovalRule(k);
        policyLimits(k);
        budgetExhaustion(k);
        unauthorizedCostCenter(k);
        duplicateSubmission(k);
        invalidCurrency(k);
        policyEffectiveDate(k);
        receiptProblems(k);
        intakeRejections(k);
        multiLevelApproval(k);
    }

    // ------------------------------------------------------------------ helpers

    private static ExpensePolicy policy(ScenarioKit k, Employee e, String category, LocalDate date) {
        return k.factory.policy(category, e.getCountry(), e.getEmploymentType(), date)
                .orElseThrow(() -> new IllegalStateException("No policy for " + category + "/" + e.getCountry() + " on " + date));
    }

    private static BigDecimal frac(BigDecimal value, double fraction) {
        return value.multiply(BigDecimal.valueOf(fraction)).setScale(2, RoundingMode.HALF_UP);
    }

    private static String primaryCc(ScenarioKit k, Employee e, LocalDate date) {
        return k.factory.primaryCostCenter(e, date).orElseThrow();
    }

    private static String bigTicketCc(ScenarioKit k, Employee e, LocalDate date) {
        return k.scenarioCostCenter(e).orElseGet(() -> primaryCc(k, e, date));
    }

    /** A single small meal at a fraction of the per-transaction limit with a receipt. */
    private static ExpenseSubmission.Line meal(ScenarioKit k, Employee e, String merchant, LocalDate date, double fraction, String seed) {
        ExpensePolicy p = policy(k, e, "MEALS", date);
        return k.lineLocal("MEALS", merchant, date, frac(p.getMaximumPerTransaction(), fraction), k.factory.currencyOf(e),
                k.factory.receipt(seed, k.rng), null);
    }

    private static String expectedReimbursement(String finalStatus) {
        return finalStatus.equals("APPROVED") ? "PENDING_PAYMENT" : "NONE";
    }

    /** A subject for a big-ticket scenario spanning {@code from}..{@code to}: at most one such scenario per employee, category and month. */
    private static Employee fullLimitSubject(ScenarioKit k, LocalDate from, LocalDate to, String... categories) {
        return k.bigTicketSubject(x -> !x.getCountry().equals("IN"), from, to, categories).orElseGet(k::anyPool);
    }

    // ------------------------------------------------------------------ scenario 3

    private static void outsideEmploymentPeriod(ScenarioKit k) {
        List<Employee> joiners = k.filter(e -> e.getEmploymentStatus() == EmploymentStatus.ACTIVE && e.getManagerId() != null
                && e.getJoiningDate().isAfter(LocalDate.of(k.ctx.settings.year(), 3, 1)) && e.getJoiningDate().isBefore(k.ref.minusDays(30)));
        List<Employee> leavers = k.filter(e -> e.getEmploymentStatus() == EmploymentStatus.TERMINATED
                && e.getTerminationDate().isBefore(k.ref.minusDays(12)));
        int half = Math.max(1, k.count(12) / 2);
        for (int i = 0; i < half && !joiners.isEmpty(); i++) {
            Employee e = joiners.get(i % joiners.size());
            LocalDate txn = k.weekday(e.getJoiningDate().minusDays(10 + (i * 3L) % 20));
            if (txn.isBefore(k.ctx.settings.simulationStart())) {
                continue;
            }
            ClaimPlan p = k.claim(e, primaryCc(k, e, k.ref.minusDays(4)), k.submitTime(k.dayBeforeRef(5, 3)),
                    meal(k, e, "Cafe Roma", txn, 0.4, "outside-pre-" + i));
            p.truth = k.anomaly(k.nextScenarioId("OUTSIDE-EMP"), "OUTSIDE_EMPLOYMENT_PERIOD", "REJECTED", "OUTSIDE_EMPLOYMENT_PERIOD", null, "NONE",
                    "HIGH", "Transaction dated " + txn + " is before the employee joined on " + e.getJoiningDate()
                            + "; the transaction is preserved as a submitted record", "DECISION_REASON", List.of(), "EX-07");
        }
        for (int i = 0; i < half && !leavers.isEmpty(); i++) {
            Employee e = leavers.get(i % leavers.size());
            LocalDate txn = k.weekday(e.getTerminationDate().plusDays(5 + (i * 4L) % 20));
            if (txn.isAfter(k.ref.minusDays(4))) {
                continue;
            }
            ClaimPlan p = k.claim(e, primaryCc(k, e, txn.plusDays(2)), k.submitTime(txn.plusDays(2)),
                    meal(k, e, "Noodle Bar", txn, 0.4, "outside-post-" + i));
            p.truth = k.anomaly(k.nextScenarioId("OUTSIDE-EMP"), "OUTSIDE_EMPLOYMENT_PERIOD", "REJECTED", "OUTSIDE_EMPLOYMENT_PERIOD", null, "NONE",
                    "HIGH", "Transaction dated " + txn + " is after the employee's termination on " + e.getTerminationDate(),
                    "DECISION_REASON", List.of(), "EX-07");
        }
    }

    // ------------------------------------------------------------------ scenario 4

    private static void splitClaims(ScenarioKit k) {
        int total = k.count(8);
        int ce = (int) Math.ceil(total * 0.5);
        int travel = (int) Math.ceil(total * 0.25);
        int training = Math.max(0, total - ce - travel);
        int n = 0;
        for (int i = 0; i < ce; i++) {
            splitCluster(k, n++, "CLIENT_ENTERTAINMENT", new double[] {300, 250}, i % 2 == 0 ? new double[] {200, 180, 160} : null);
        }
        for (int i = 0; i < travel; i++) {
            splitCluster(k, n++, "TRAVEL", new double[] {1500, 1200}, null);
        }
        for (int i = 0; i < training; i++) {
            splitCluster(k, n++, "TRAINING", new double[] {2900, 2800}, null);
        }
        // negative controls: look-alike claims that must NOT be flagged
        int controls = k.count(8);
        for (int i = 0; i < controls; i++) {
            splitControl(k, i);
        }
    }

    private static void splitCluster(ScenarioKit k, int n, String category, double[] primary, double[] alternative) {
        double[] amounts = alternative != null ? alternative : primary;
        LocalDate first = k.dayBeforeRef(120, 50);
        Employee e = fullLimitSubject(k, first, first.plusDays(amounts.length + 3L), category);
        String merchant = k.factory.merchant(category, k.rng) + " " + (n + 1);
        List<ClaimPlan> cluster = new ArrayList<>();
        BigDecimal combined = BigDecimal.ZERO;
        boolean ce = category.equals("CLIENT_ENTERTAINMENT");
        for (int i = 0; i < amounts.length; i++) {
            LocalDate day = k.weekday(first.plusDays(i));
            ClaimPlan p = k.claim(e, bigTicketCc(k, e, day), Timeline.at(day, 10, 5 * i),
                    k.line(e, category, merchant, day, amounts[i], true, "split-" + n + "-" + i));
            cluster.add(p);
            combined = combined.add(k.factory.toUsd(k.factory.currencyOf(e), p.submission.lines().get(0).amount()));
            // approvers act only after the whole pattern has been submitted
            p.decisions.add(new me.niteshh.nexoraflow.generator.plan.Plans.TimedDecision(
                    Timeline.at(first.plusDays(amounts.length + 3L), 11, 0), me.niteshh.nexoraflow.generator.plan.Plans.Verdict.APPROVE));
        }
        String scenario = k.nextScenarioId("SPLIT");
        String combinedPath = k.expensePath(e, combined, ce);
        for (ClaimPlan p : cluster) {
            List<String> related = new ArrayList<>();
            cluster.forEach(o -> {
                if (o != p) {
                    related.add(o.key);
                }
            });
            p.truth = k.anomaly(scenario, "SUSPICIOUS_SPLIT_CLAIM", "FLAGGED", "SUSPICIOUS_SPLIT_CLAIM", null, "NONE", "HIGH",
                    cluster.size() + " related " + category + " claims at the same merchant within 3 days each stay below an approval threshold but total "
                            + combined + " USD; the combined amount would require: " + combinedPath, "ANOMALY_RECORD", related, "EX-18");
        }
    }

    private static void splitControl(ScenarioKit k, int i) {
        String category = "CLIENT_ENTERTAINMENT";
        LocalDate day = k.dayBeforeRef(110, 55);
        Employee e = fullLimitSubject(k, day, day.plusDays(16), category);
        Employee other = fullLimitSubject(k, day, day.plusDays(16), category);
        String scenario = k.nextScenarioId("SPLIT-CONTROL");
        List<ClaimPlan> pair = new ArrayList<>();
        String why;
        switch (i % 4) {
            case 0 -> { // same merchant, far apart in time
                String m = k.factory.merchant(category, k.rng) + " ctl" + i;
                pair.add(controlClaim(k, e, category, m, day, 300, "ctl-" + i + "-a"));
                pair.add(controlClaim(k, e, category, m, k.weekday(day.plusDays(14)), 250, "ctl-" + i + "-b"));
                why = "same merchant but 14 days apart: outside the matching window";
            }
            case 1 -> { // same day, different merchants
                pair.add(controlClaim(k, e, category, k.factory.merchant(category, k.rng) + " ctl" + i + "x", day, 300, "ctl-" + i + "-a"));
                pair.add(controlClaim(k, e, category, k.factory.merchant(category, k.rng) + " ctl" + i + "y", day, 250, "ctl-" + i + "-b"));
                why = "same day but different merchants";
            }
            case 2 -> { // adjacent days, same merchant, small total
                String m = k.factory.merchant(category, k.rng) + " ctl" + i;
                pair.add(controlClaim(k, e, category, m, day, 120, "ctl-" + i + "-a"));
                pair.add(controlClaim(k, e, category, m, k.weekday(day.plusDays(1)), 150, "ctl-" + i + "-b"));
                why = "same merchant on adjacent days but the combined amount stays below the threshold";
            }
            default -> { // adjacent days, same merchant, different employees
                String m = k.factory.merchant(category, k.rng) + " ctl" + i;
                pair.add(controlClaim(k, e, category, m, day, 300, "ctl-" + i + "-a"));
                pair.add(controlClaim(k, other, category, m, k.weekday(day.plusDays(1)), 250, "ctl-" + i + "-b"));
                why = "same merchant and dates but submitted by two different employees";
            }
        }
        for (ClaimPlan p : pair) {
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(k.ctx.org.get(p.requester)), p.submission.lines().get(0).amount());
            Employee who = k.ctx.org.get(p.requester);
            int levels = k.expenseLevels(who, usd, true);
            Timeline.Result r = k.approveAll(p, levels);
            p.truth = k.designed(scenario, "SPLIT_CLAIM_CONTROL", r.finalStatus(), r.finalStatus().equals("REJECTED") ? "REJECTED_BY_APPROVER" : "",
                    k.expensePath(who, usd, true), expectedReimbursement(r.finalStatus()),
                    "Negative control (" + why + "): must NOT be flagged as a split claim", "NONE", List.of(), "EX-18");
        }
    }

    private static ClaimPlan controlClaim(ScenarioKit k, Employee e, String category, String merchant, LocalDate day, double usd, String seed) {
        return k.claim(e, bigTicketCc(k, e, day), Timeline.at(day, 10, 30), k.line(e, category, merchant, day, usd, true, seed));
    }

    // ------------------------------------------------------------------ scenario 5

    private static void missingCostCenter(ScenarioKit k) {
        Optional<CostCenter> closed = k.ctx.costCenters.values().stream()
                .filter(c -> c.getStatus() == CostCenterStatus.CLOSED).sorted(java.util.Comparator.comparing(CostCenter::getCostCenterId)).findFirst();
        int total = k.count(10);
        for (int i = 0; i < total; i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(8, 3);
            String ref;
            String issue = "MISSING_COST_CENTER";
            String what;
            if (i % 5 < 2) {
                ref = "";
                what = "blank";
            } else if (i % 5 < 4 || closed.isEmpty()) {
                ref = "CC-99" + String.format("%02d", i);
                what = "unknown ('" + ref + "')";
            } else {
                ref = closed.get().getCostCenterId();
                issue = "CLOSED_COST_CENTER";
                what = "closed (" + ref + ")";
            }
            ClaimPlan p = k.claim(e, ref, k.submitTime(day), meal(k, e, "Green Fork", day, 0.4, "cc-" + i));
            p.truth = k.anomaly(k.nextScenarioId("COST-CENTER"), issue, "ON_HOLD", "INVALID_COST_CENTER", null, "NONE", "MEDIUM",
                    "The submitted cost center is " + what + ". The submitted value is preserved; no invalid foreign key is stored; the claim is held",
                    "DECISION_REASON", List.of(), "EX-15");
        }
    }

    // ------------------------------------------------------------------ additional scenarios

    private static void missingReceipt(ScenarioKit k) {
        for (int i = 0; i < k.count(15); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(12, 3);
            String category = i % 3 == 2 ? "OFFICE_SUPPLIES" : "MEALS";
            ExpensePolicy p = policy(k, e, category, day);
            BigDecimal amount = frac(p.getMaximumPerTransaction(), category.equals("MEALS") ? 0.42 : 0.5);
            ClaimPlan plan = k.claim(e, primaryCc(k, e, day), k.submitTime(day),
                    k.lineLocal(category, k.factory.merchant(category, k.rng), day, amount, k.factory.currencyOf(e), null, null));
            plan.truth = k.anomaly(k.nextScenarioId("NO-RECEIPT"), "MISSING_RECEIPT", "FLAGGED", "RECEIPT_MISSING", null, "NONE", "MEDIUM",
                    "Amount " + amount + " exceeds the receipt threshold " + p.getReceiptThreshold() + " but no receipt was attached",
                    "DECISION_REASON", List.of(), "EX-12");
        }
    }

    private static void duplicateReceipt(ScenarioKit k) {
        for (int i = 0; i < k.count(8); i++) {
            Employee e1 = k.anyPool();
            Employee e2 = k.anyPool();
            LocalDate d1 = k.dayBeforeRef(20, 12);
            LocalDate d2 = k.weekday(d1.plusDays(3));
            ExpensePolicy p1 = policy(k, e1, "MEALS", d1);
            ExpensePolicy p2 = policy(k, e2, "MEALS", d2);
            ExpenseSubmission.ReceiptUpload shared = k.factory.receipt("dup-receipt-" + i, k.rng);
            ClaimPlan a = k.claim(e1, primaryCc(k, e1, d1), k.submitTime(d1), k.lineLocal("MEALS", "Spice Route", d1,
                    frac(p1.getMaximumPerTransaction(), 0.4), k.factory.currencyOf(e1), shared, null));
            ClaimPlan b = k.claim(e2, primaryCc(k, e2, d2), k.submitTime(d2), k.lineLocal("MEALS", "Taco Lane", d2,
                    frac(p2.getMaximumPerTransaction(), 0.42), k.factory.currencyOf(e2), shared, null));
            String scenario = k.nextScenarioId("DUP-RECEIPT");
            Timeline.Result ra = k.approveAll(a, 1);
            BigDecimal usdA = k.factory.toUsd(k.factory.currencyOf(e1), a.submission.lines().get(0).amount());
            a.truth = k.designed(scenario, "NONE", ra.finalStatus(), "", k.expensePath(e1, usdA, false), expectedReimbursement(ra.finalStatus()),
                    "The original claim that owns the receipt", "NONE", List.of(), "EX-01..EX-19");
            b.truth = k.anomaly(scenario, "DUPLICATE_RECEIPT", "FLAGGED", "DUPLICATE_RECEIPT", null, "NONE", "HIGH",
                    "The same receipt (identical hash) was already attached to another claim", "DECISION_REASON", List.of(a.key), "EX-13");
        }
    }

    private static void invalidDateRange(ScenarioKit k) {
        for (int i = 0; i < k.count(6); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(10, 4);
            ExpenseSubmission.Line line = meal(k, e, "Burger Barn", k.weekday(day.minusDays(1)), 0.4, "inv-range-" + i);
            ClaimPlan p = k.claim(e, primaryCc(k, e, day), k.factory.currencyOf(e), k.submitTime(day), List.of(line), day, day.minusDays(5));
            p.truth = k.anomaly(k.nextScenarioId("INVALID-RANGE-EXP"), "INVALID_DATE_RANGE", "REJECTED", "INVALID_DATE_RANGE", null, "NONE", "LOW",
                    "The expense period ends before it starts; the submission is preserved as a rejected claim", "DECISION_REASON", List.of(), "EX-02");
        }
    }

    private static void inactiveEmployee(ScenarioKit k) {
        List<Employee> inactive = k.employeesWithStatus(EmploymentStatus.INACTIVE);
        for (int i = 0; i < k.count(4) && !inactive.isEmpty(); i++) {
            Employee e = inactive.get(i % inactive.size());
            LocalDate day = k.dayBeforeRef(9, 3);
            Optional<String> cc = k.factory.primaryCostCenter(e, day);
            if (cc.isEmpty() || e.getJoiningDate().isAfter(day.minusDays(7))) {
                continue;
            }
            ClaimPlan p = k.claim(e, cc.get(), k.submitTime(day), meal(k, e, "Dim Sum House", day.minusDays(1), 0.4, "inactive-" + i));
            p.truth = k.anomaly(k.nextScenarioId("INACTIVE-EMP-EXP"), "INACTIVE_EMPLOYEE", "REJECTED", "INACTIVE_EMPLOYEE", null, "NONE", "MEDIUM",
                    "The employee is INACTIVE and cannot submit expense claims", "DECISION_REASON", List.of(), "EX-01");
        }
    }

    private static void inactiveApprovalRule(ScenarioKit k) {
        for (int i = 0; i < k.count(6); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(100, 60);
            ExpensePolicy p = policy(k, e, "TRAVEL", day);
            double usd = 1100 + (i * 90) % 600;
            BigDecimal local = k.factory.local(k.factory.currencyOf(e), usd);
            if (local.compareTo(p.getMaximumPerTransaction()) > 0) {
                local = frac(p.getMaximumPerTransaction(), 0.9);
            }
            BigDecimal usdAmount = k.factory.toUsd(k.factory.currencyOf(e), local);
            if (usdAmount.compareTo(new BigDecimal("500")) <= 0 || usdAmount.compareTo(new BigDecimal("2000")) > 0) {
                continue;
            }
            ClaimPlan plan = k.claim(e, bigTicketCc(k, e, day), Timeline.at(day, 14, 10), k.lineLocal("TRAVEL", k.factory.merchant("TRAVEL", k.rng),
                    day.minusDays(2), local, k.factory.currencyOf(e), k.factory.receipt("inactive-rule-" + i, k.rng), null));
            Timeline.Result r = k.approveAll(plan, 1);
            plan.truth = k.anomaly(k.nextScenarioId("INACTIVE-RULE"), "INACTIVE_APPROVAL_POLICY", r.finalStatus(), "", k.expensePath(e, usdAmount, false),
                    expectedReimbursement(r.finalStatus()), "LOW", "A decommissioned approval rule (extra Finance Head step) and an expired 2025 rule exist for this "
                            + "amount range; neither may be applied: the route must be the manager only", "APPROVAL_PATH", List.of(), "AR-EX-008,AR-EX-009");
        }
    }

    private static void policyLimits(ScenarioKit k) {
        String[] categories = {"MEALS", "TRAVEL", "OFFICE_SUPPLIES", "LODGING"};
        for (int i = 0; i < k.count(8); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(14, 4);
            String category = categories[i % categories.length];
            ExpensePolicy p = policy(k, e, category, day);
            BigDecimal amount = frac(p.getMaximumPerTransaction(), 1.25);
            ClaimPlan plan = k.claim(e, bigTicketCc(k, e, day), k.submitTime(day), k.lineLocal(category, k.factory.merchant(category, k.rng), day,
                    amount, k.factory.currencyOf(e), k.factory.receipt("limit-txn-" + i, k.rng), null));
            plan.truth = k.anomaly(k.nextScenarioId("LIMIT-TXN"), "TRANSACTION_LIMIT_EXCEEDED", "REJECTED", "POLICY_LIMIT_EXCEEDED", null, "NONE", "MEDIUM",
                    category + " amount " + amount + " exceeds the per-transaction limit " + p.getMaximumPerTransaction(), "DECISION_REASON", List.of(), "EX-09");
        }
        for (int i = 0; i < k.count(4); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(14, 4);
            ExpensePolicy p = policy(k, e, "MEALS", day);
            BigDecimal each = frac(p.getMaximumPerTransaction(), 0.9);
            ClaimPlan plan = k.claim(e, primaryCc(k, e, day), k.submitTime(day),
                    k.lineLocal("MEALS", "Pho Corner", day, each, k.factory.currencyOf(e), k.factory.receipt("limit-day-a-" + i, k.rng), null),
                    k.lineLocal("MEALS", "Urban Tandoor", day, each, k.factory.currencyOf(e), k.factory.receipt("limit-day-b-" + i, k.rng), null));
            plan.truth = k.anomaly(k.nextScenarioId("LIMIT-DAY"), "DAILY_LIMIT_EXCEEDED", "REJECTED", "POLICY_LIMIT_EXCEEDED", null, "NONE", "MEDIUM",
                    "Two meals on one day total " + each.multiply(BigDecimal.valueOf(2)) + ", above the daily limit " + p.getMaximumDailyAmount(),
                    "DECISION_REASON", List.of(), "EX-10");
        }
        for (int i = 0; i < k.count(3); i++) {
            Employee e = k.anyPool();
            LocalDate month = k.dayBeforeRef(90, 55);
            LocalDate d1 = k.weekday(month.withDayOfMonth(6));
            LocalDate d2 = k.weekday(month.withDayOfMonth(15));
            ExpensePolicy p = policy(k, e, "OFFICE_SUPPLIES", d1);
            BigDecimal each = frac(p.getMaximumPerTransaction(), 0.9);
            String cc = bigTicketCc(k, e, d1);
            ClaimPlan first = k.claim(e, cc, Timeline.at(d1, 10, 0),
                    k.lineLocal("OFFICE_SUPPLIES", "OfficeWorld", d1, each, k.factory.currencyOf(e), k.factory.receipt("limit-mo-a-" + i, k.rng), null),
                    k.lineLocal("OFFICE_SUPPLIES", "TechMart", d1, each, k.factory.currencyOf(e), k.factory.receipt("limit-mo-b-" + i, k.rng), null));
            ClaimPlan second = k.claim(e, cc, Timeline.at(d2, 10, 0), k.lineLocal("OFFICE_SUPPLIES", "PrintPoint", d2, each,
                    k.factory.currencyOf(e), k.factory.receipt("limit-mo-c-" + i, k.rng), null));
            String scenario = k.nextScenarioId("LIMIT-MONTH");
            BigDecimal usdFirst = k.factory.toUsd(k.factory.currencyOf(e), each.multiply(BigDecimal.valueOf(2)));
            Timeline.Result r = k.approveAll(first, k.expenseLevels(e, usdFirst, false));
            first.truth = k.designed(scenario, "NONE", r.finalStatus(), "", k.expensePath(e, usdFirst, false), expectedReimbursement(r.finalStatus()),
                    "Earlier claim that consumes most of the monthly limit", "NONE", List.of(), "EX-01..EX-19");
            second.truth = k.anomaly(scenario, "MONTHLY_LIMIT_EXCEEDED", "REJECTED", "POLICY_LIMIT_EXCEEDED", null, "NONE", "MEDIUM",
                    "Together with the earlier claim the month's OFFICE_SUPPLIES total exceeds the monthly limit " + p.getMaximumMonthlyAmount(),
                    "DECISION_REASON", List.of(first.key), "EX-11");
        }
    }

    // ------------------------------------------------------------------ monthly budget exhaustion

    private static void budgetExhaustion(ScenarioKit k) {
        List<CostCenter> tight = k.ctx.costCenters.values().stream().filter(c -> c.getCostCenterCode().startsWith("SPL-"))
                .sorted(java.util.Comparator.comparing(CostCenter::getCostCenterId)).toList();
        int total = Math.min(k.count(6), tight.size());
        for (int i = 0; i < total; i++) {
            CostCenter cc = tight.get(i);
            Optional<Employee> subject = k.ctx.pool.members().stream().filter(m -> k.ctx.assignments.stream().anyMatch(a ->
                    a.getEmployeeId().equals(m.getEmployeeId()) && a.getCostCenterId().equals(cc.getCostCenterId())
                            && a.getAssignmentType() == AssignmentType.SECONDARY)).findFirst();
            if (subject.isEmpty() || subject.get().getEmploymentType() != EmploymentType.FULL_TIME) {
                continue;
            }
            Employee e = subject.get();
            String ccy = k.factory.currencyOf(e);
            LocalDate d1 = k.weekday(k.ref.minusDays(16 - i % 3));
            LocalDate d2 = k.weekday(k.ref.minusDays(8 - i % 3));
            ExpensePolicy p = policy(k, e, "TRAVEL", d1);
            double cap = p.getMaximumPerTransaction().multiply(k.factory.rate(ccy)).doubleValue() * 0.92;
            double x1 = Math.min(2000, cap);
            double x2 = Math.min(cap, 3000 - x1 + 300);
            if (x1 + x2 <= 3000) {
                continue; // cannot exceed the 3,000 USD budget within the per-transaction limit
            }
            ClaimPlan first = k.claim(e, cc.getCostCenterId(), Timeline.at(d1, 10, 0),
                    k.line(e, "TRAVEL", "Skyway Air " + (i + 1), d1.minusDays(1), x1, true, "budget-a-" + i));
            ClaimPlan second = k.claim(e, cc.getCostCenterId(), Timeline.at(d2, 10, 0),
                    k.line(e, "TRAVEL", "Rail Europe " + (i + 1), d2.minusDays(1), x2, true, "budget-b-" + i));
            String scenario = k.nextScenarioId("BUDGET");
            BigDecimal usd1 = k.factory.toUsd(ccy, first.submission.lines().get(0).amount());
            Timeline.Result r = k.approveAll(first, k.expenseLevels(e, usd1, false));
            first.truth = k.designed(scenario, "NONE", r.finalStatus(), "", k.expensePath(e, usd1, false), expectedReimbursement(r.finalStatus()),
                    "First claim of the month consumes most of the 3,000 USD budget of " + cc.getCostCenterCode(), "NONE", List.of(), "EX-01..EX-19");
            second.truth = k.anomaly(scenario, "MONTHLY_BUDGET_EXHAUSTED", "ON_HOLD", "INSUFFICIENT_BUDGET", null, "NONE", "MEDIUM",
                    "Remaining budget of " + cc.getCostCenterCode() + " for the month (3,000 USD minus the first claim) cannot cover the second claim",
                    "DECISION_REASON", List.of(first.key), "EX-17");
        }
    }

    private static void unauthorizedCostCenter(ScenarioKit k) {
        List<CostCenter> regular = k.ctx.costCenters.values().stream().filter(c -> c.getStatus() == CostCenterStatus.ACTIVE
                && !c.getCostCenterCode().startsWith("SPL-") && !c.getCostCenterCode().startsWith("PRJ-"))
                .sorted(java.util.Comparator.comparing(CostCenter::getCostCenterId)).toList();
        for (int i = 0; i < k.count(8); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(12, 3);
            Optional<CostCenter> foreign = regular.stream().filter(c -> !c.getDepartmentId().equals(e.getDepartmentId())
                    && k.ctx.assignments.stream().noneMatch(a -> a.getEmployeeId().equals(e.getEmployeeId()) && a.getCostCenterId().equals(c.getCostCenterId())))
                    .skip(i % 3).findFirst();
            if (foreign.isEmpty()) {
                continue;
            }
            ClaimPlan p = k.claim(e, foreign.get().getCostCenterId(), k.submitTime(day), meal(k, e, "Harvest Table", day, 0.4, "unauth-cc-" + i));
            p.truth = k.anomaly(k.nextScenarioId("UNAUTH-CC"), "UNAUTHORIZED_COST_CENTER_USAGE", "FLAGGED", "UNAUTHORIZED_COST_CENTER", null, "NONE",
                    "MEDIUM", "The employee is not assigned to cost center " + foreign.get().getCostCenterCode(), "DECISION_REASON", List.of(), "EX-16");
        }
    }

    private static void duplicateSubmission(ScenarioKit k) {
        for (int i = 0; i < k.count(8); i++) {
            Employee e = k.anyPool();
            LocalDate d = k.dayBeforeRef(20, 10);
            ExpensePolicy policy = policy(k, e, "MEALS", d);
            BigDecimal amount = frac(policy.getMaximumPerTransaction(), 0.4);
            String merchant = k.factory.merchant("MEALS", k.rng) + " dup" + i;
            ClaimPlan a = k.claim(e, primaryCc(k, e, d), k.submitTime(d), k.lineLocal("MEALS", merchant, d, amount, k.factory.currencyOf(e),
                    k.factory.receipt("dup-sub-a-" + i, k.rng), null));
            LocalDate d2 = k.weekday(d.plusDays(2));
            ClaimPlan b = k.claim(e, primaryCc(k, e, d2), k.submitTime(d2), k.lineLocal("MEALS", merchant, d, amount, k.factory.currencyOf(e),
                    k.factory.receipt("dup-sub-b-" + i, k.rng), null));
            String scenario = k.nextScenarioId("DUP-SUBMISSION");
            Timeline.Result r = k.approveAll(a, 1);
            BigDecimal usd = k.factory.toUsd(k.factory.currencyOf(e), amount);
            a.truth = k.designed(scenario, "NONE", r.finalStatus(), "", k.expensePath(e, usd, false), expectedReimbursement(r.finalStatus()),
                    "The original submission", "NONE", List.of(), "EX-01..EX-19");
            b.truth = k.anomaly(scenario, "DUPLICATE_EXPENSE_SUBMISSION", "FLAGGED", "DUPLICATE_SUBMISSION", null, "NONE", "MEDIUM",
                    "Identical merchant, date, amount and category were already submitted by the same employee", "DECISION_REASON", List.of(a.key), "EX-14");
        }
    }

    private static void invalidCurrency(ScenarioKit k) {
        for (int i = 0; i < k.count(6); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(10, 3);
            String own = k.factory.currencyOf(e);
            String bad = i % 2 == 0 ? "ZZZ" : (own.equals("USD") ? "INR" : "USD");
            ExpensePolicy p = policy(k, e, "MEALS", day);
            BigDecimal amount = frac(p.getMaximumPerTransaction(), 0.4);
            ClaimPlan plan = k.claim(e, primaryCc(k, e, day), bad, k.submitTime(day), List.of(k.lineLocal("MEALS", "Sakura Kitchen", day, amount, bad,
                    k.factory.receipt("ccy-" + i, k.rng), null)), day, day);
            plan.truth = k.anomaly(k.nextScenarioId("CURRENCY"), "INVALID_CURRENCY", "REJECTED", "INVALID_CURRENCY", null, "NONE", "MEDIUM",
                    "Currency " + bad + " is " + (bad.equals("ZZZ") ? "unsupported" : "inconsistent with the employee's country currency " + own),
                    "DECISION_REASON", List.of(), "EX-04");
        }
    }

    private static void policyEffectiveDate(ScenarioKit k) {
        List<Employee> singapore = k.filter(e -> e.getCountry().equals("SG") && e.getEmploymentType() == EmploymentType.FULL_TIME
                && e.getEmploymentStatus() == EmploymentStatus.ACTIVE && e.getJoiningDate().isBefore(LocalDate.of(k.ctx.settings.year(), 1, 5))
                && e.getManagerId() != null);
        Optional<ExpensePolicy> sgTraining = k.ctx.expensePolicies.stream()
                .filter(p -> p.getCountry().equals("SG") && p.getExpenseCategory().name().equals("TRAINING")).findFirst();
        for (int i = 0; i < k.count(6) && !singapore.isEmpty() && sgTraining.isPresent(); i++) {
            Employee e = singapore.get(i % singapore.size());
            LocalDate txn = k.weekday(LocalDate.of(k.ctx.settings.year(), 1, 12).plusDays(i * 3L));
            LocalDate day = k.dayBeforeRef(10, 3);
            Optional<String> cc = k.factory.primaryCostCenter(e, day);
            if (cc.isEmpty() || !txn.isBefore(sgTraining.get().getEffectiveFrom())) {
                continue;
            }
            ClaimPlan plan = k.claim(e, cc.get(), k.factory.currencyOf(e), k.submitTime(day), List.of(k.lineLocal("TRAINING", "Pinnacle Training", txn,
                    frac(sgTraining.get().getMaximumPerTransaction(), 0.3), k.factory.currencyOf(e), k.factory.receipt("policy-date-" + i, k.rng), null)),
                    txn, txn);
            plan.truth = k.anomaly(k.nextScenarioId("POLICY-DATE-EXP"), "POLICY_EFFECTIVE_DATE_MISMATCH", "REJECTED", "POLICY_NOT_EFFECTIVE", null, "NONE",
                    "MEDIUM", "The Singapore TRAINING policy became effective on " + sgTraining.get().getEffectiveFrom() + "; the transaction is dated " + txn,
                    "DECISION_REASON", List.of(), "EX-08");
        }
    }

    private static void receiptProblems(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(11, 3);
            ExpensePolicy p = policy(k, e, "MEALS", day);
            BigDecimal amount = frac(p.getMaximumPerTransaction(), 0.42);
            ExpenseSubmission.ReceiptUpload upload = new ExpenseSubmission.ReceiptUpload("scan-" + i + ".exe", "EXE", 52_000,
                    me.niteshh.nexoraflow.generator.plan.ClaimFactory.sha256("bad-type-" + k.ctx.seeds.seed() + "-" + i), "email-forward");
            ClaimPlan plan = k.claim(e, primaryCc(k, e, day), k.submitTime(day), k.lineLocal("MEALS", "Olive & Thyme", day, amount, k.factory.currencyOf(e), upload, null));
            plan.truth = k.anomaly(k.nextScenarioId("RECEIPT-TYPE"), "UNSUPPORTED_RECEIPT_TYPE", "FLAGGED", "RECEIPT_INVALID", null, "NONE", "LOW",
                    "The attached receipt is an .exe file, which is not a supported receipt type", "DECISION_REASON", List.of(), "EX-12");
        }
        for (int i = 0; i < k.count(4); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(11, 3);
            ExpensePolicy p = policy(k, e, "MEALS", day);
            BigDecimal amount = frac(p.getMaximumPerTransaction(), 0.42);
            ClaimPlan plan = k.claim(e, primaryCc(k, e, day), k.submitTime(day), k.lineLocal("MEALS", "Bread & Butter", day, amount, k.factory.currencyOf(e),
                    null, "RCP-99" + String.format("%04d", i)));
            plan.truth = k.anomaly(k.nextScenarioId("RECEIPT-REF"), "INVALID_RECEIPT_REFERENCE", "FLAGGED", "INVALID_RECEIPT_REFERENCE", null, "NONE",
                    "MEDIUM", "The submitted receipt reference does not exist; the original value is preserved", "DECISION_REASON", List.of(), "EX-12");
        }
    }

    private static void intakeRejections(ScenarioKit k) {
        for (int i = 0; i < k.count(4); i++) {
            String ghost = "EMP-" + String.format("%06d", 910_000 + i);
            LocalDate day = k.dayBeforeRef(8, 2);
            ExpenseSubmission sub = new ExpenseSubmission(ghost, day, day, "CC-0001", "USD", List.of(k.lineLocal("MEALS", "Cafe Roma", day,
                    new BigDecimal("30.00"), "USD", null, null)));
            ClaimPlan p = new ClaimPlan(k.ctx.nextClaimKey(), ghost, k.submitTime(day), sub);
            k.plans.add(p);
            p.truth = k.anomaly(k.nextScenarioId("UNKNOWN-EMP-EXP"), "INVALID_EMPLOYEE_REFERENCE", "REJECTED_AT_INTAKE", "EMPLOYEE_NOT_FOUND", null, "NONE",
                    "MEDIUM", "The claim references an employee that does not exist; it is preserved in staging, never as a canonical claim", "INTAKE", List.of(), "INTAKE");
        }
        for (int i = 0; i < k.count(2); i++) {
            Employee e = k.anyPool();
            LocalDate day = k.dayBeforeRef(8, 2);
            ExpenseSubmission sub = new ExpenseSubmission(e.getEmployeeId(), day, day, primaryCc(k, e, day), k.factory.currencyOf(e), List.of(
                    k.lineLocal("GAMBLING", "Lucky Strike Casino", day, new BigDecimal("40.00"), k.factory.currencyOf(e), null, null)));
            ClaimPlan p = new ClaimPlan(k.ctx.nextClaimKey(), e.getEmployeeId(), k.submitTime(day), sub);
            k.plans.add(p);
            p.truth = k.anomaly(k.nextScenarioId("CATEGORY"), "UNSUPPORTED_CATEGORY", "REJECTED_AT_INTAKE", "UNSUPPORTED_CATEGORY", null, "NONE", "LOW",
                    "Expense category GAMBLING is not supported; the submission is preserved in staging", "INTAKE", List.of(), "INTAKE");
        }
    }

    private static void multiLevelApproval(ScenarioKit k) {
        int total = k.count(6);
        for (int i = 0; i < total; i++) {
            LocalDate day = k.dayBeforeRef(110, 70);
            boolean large = i % 2 == 1;
            Employee e = large ? fullLimitSubject(k, day.minusDays(6), day, "TRAVEL", "TRAINING") : fullLimitSubject(k, day.minusDays(6), day, "TRAVEL");
            String cc = bigTicketCc(k, e, day);
            ClaimPlan p = large
                    ? k.claim(e, cc, Timeline.at(day, 15, 0), k.line(e, "TRAVEL", "Meridian Airways " + i, day.minusDays(6), 2300, true, "ml-a-" + i),
                    k.line(e, "TRAVEL", "Orbit Airlines " + i, day.minusDays(2), 2300, true, "ml-b-" + i),
                    k.line(e, "TRAINING", "Summit Learning Academy " + i, day.minusDays(4), 2800, true, "ml-c-" + i))
                    : k.claim(e, cc, Timeline.at(day, 15, 0), k.line(e, "TRAVEL", "Meridian Airways " + i, day.minusDays(6), 2200, true, "ml-a-" + i),
                    k.line(e, "TRAVEL", "Orbit Airlines " + i, day.minusDays(2), 2200, true, "ml-b-" + i));
            BigDecimal usd = BigDecimal.ZERO;
            for (ExpenseSubmission.Line l : p.submission.lines()) {
                usd = usd.add(l.amount());
            }
            usd = k.factory.toUsd(k.factory.currencyOf(e), usd);
            int levels = k.expenseLevels(e, usd, false);
            Timeline.Result r = k.approveAll(p, levels);
            p.truth = k.designed(k.nextScenarioId("MULTI-LEVEL"), "MULTI_LEVEL_APPROVAL", r.finalStatus(), "", k.expensePath(e, usd, false),
                    expectedReimbursement(r.finalStatus()), "A claim of " + usd + " USD needs " + levels + " approval levels in the configured order",
                    "APPROVAL_PATH", List.of(), "AR-EX-003..AR-EX-006");
        }
    }
}
