package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.approval.ApprovalThresholds;
import me.niteshh.nexoraflow.budget.BudgetService;
import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.rules.DecisionAggregator;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;
import me.niteshh.nexoraflow.common.rules.RuleSeverity;
import me.niteshh.nexoraflow.configuration.Country;
import me.niteshh.nexoraflow.configuration.CountryRepository;
import me.niteshh.nexoraflow.configuration.CurrencyService;
import me.niteshh.nexoraflow.configuration.NexoraProperties;
import me.niteshh.nexoraflow.costcenter.CostCenter;
import me.niteshh.nexoraflow.costcenter.CostCenterRepository;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeCostCenterRepository;
import me.niteshh.nexoraflow.employee.EmployeeRules;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.ExpensePolicy;

/**
 * Deterministic expense evaluation (rules EX-01 .. EX-18). It evaluates the claim as a unit and each line
 * individually, reading only ordinary business records (policies, budgets, other claims, receipts). It never
 * sees ground-truth labels. Approval-route resolvability (EX-19) is appended by the workflow service.
 */
@Component
public class ExpenseEvaluationEngine {

    /** Claims that no longer count toward limits, duplicates or split detection. */
    public static final List<ExpenseClaimStatus> DEAD_STATUSES = List.of(ExpenseClaimStatus.REJECTED, ExpenseClaimStatus.CANCELLED);
    /** Claims whose amounts are committed against employee limits. */
    public static final List<ExpenseClaimStatus> COMMITTED_STATUSES = List.of(ExpenseClaimStatus.PENDING_APPROVAL, ExpenseClaimStatus.APPROVED);

    private final NexoraProperties properties;
    private final CurrencyService currencies;
    private final CountryRepository countries;
    private final ExpensePolicyResolver policyResolver;
    private final ReceiptRepository receipts;
    private final ReceiptValidator receiptValidator;
    private final ExpenseLineRepository lineRepository;
    private final CostCenterRepository costCenters;
    private final EmployeeCostCenterRepository assignments;
    private final BudgetService budgets;
    private final ApprovalThresholds thresholds;

    public ExpenseEvaluationEngine(NexoraProperties properties, CurrencyService currencies, CountryRepository countries,
                                   ExpensePolicyResolver policyResolver, ReceiptRepository receipts,
                                   ReceiptValidator receiptValidator, ExpenseLineRepository lineRepository,
                                   CostCenterRepository costCenters, EmployeeCostCenterRepository assignments,
                                   BudgetService budgets, ApprovalThresholds thresholds) {
        this.properties = properties;
        this.currencies = currencies;
        this.countries = countries;
        this.policyResolver = policyResolver;
        this.receipts = receipts;
        this.receiptValidator = receiptValidator;
        this.lineRepository = lineRepository;
        this.costCenters = costCenters;
        this.assignments = assignments;
        this.budgets = budgets;
        this.thresholds = thresholds;
    }

    // ------------------------------------------------------------------ working state

    private static final class LineState {
        final ExpenseLine line;
        final List<ReasonCode> hard = new ArrayList<>();
        final List<ReasonCode> flags = new ArrayList<>();
        final List<String> messages = new ArrayList<>();
        ExpensePolicy policy;
        BigDecimal amountReporting;

        LineState(ExpenseLine line) {
            this.line = line;
        }

        void hard(ReasonCode code, String message) {
            if (!hard.contains(code)) {
                hard.add(code);
            }
            messages.add(message);
        }

        void flag(ReasonCode code, String message) {
            if (!flags.contains(code)) {
                flags.add(code);
            }
            messages.add(message);
        }
    }

    private static final class State {
        final ExpenseClaim claim;
        final Employee employee;
        final List<LineState> lines = new ArrayList<>();
        boolean currencyOk;
        boolean costCenterValid;
        BigDecimal totalReporting;
        Optional<SplitClaimDetector.Finding> split = Optional.empty();

        State(ExpenseClaim claim, Employee employee) {
            this.claim = claim;
            this.employee = employee;
        }
    }

    public ExpenseEvaluationResult evaluate(ExpenseClaim claim, Employee employee) {
        State st = new State(claim, employee);
        for (ExpenseLine line : claim.getLines()) {
            LineState ls = new LineState(line);
            if (line.getAmount().signum() > 0 && currencies.isSupported(line.getCurrency())) {
                ls.amountReporting = currencies.toReporting(line.getAmount(), line.getCurrency());
            }
            st.lines.add(ls);
        }
        st.totalReporting = currencies.isSupported(claim.getCurrency())
                ? currencies.toReporting(claim.getTotalAmount(), claim.getCurrency()) : null;

        List<RuleOutcome> outcomes = new ArrayList<>();
        outcomes.add(ex01EmployeeEligible(st));
        outcomes.add(ex02ClaimDates(st));
        outcomes.add(ex03LineCount(st));
        outcomes.add(ex04Currency(st));
        outcomes.add(ex05Amounts(st));
        outcomes.add(ex06TransactionDates(st));
        outcomes.add(ex07EmploymentPeriod(st));
        outcomes.add(ex08PolicyApplicable(st));
        outcomes.add(ex09PerTransactionLimit(st));
        outcomes.add(ex10DailyLimit(st));
        outcomes.add(ex11MonthlyLimit(st));
        outcomes.add(ex12Receipts(st));
        outcomes.add(ex13DuplicateReceipt(st));
        outcomes.add(ex14DuplicateSubmission(st));
        outcomes.add(ex15CostCenterValid(st));
        outcomes.add(ex16CostCenterAuthorized(st));
        outcomes.add(ex17Budget(st));
        outcomes.add(ex18SplitClaim(st));

        DecisionAggregator.Aggregate aggregate = DecisionAggregator.aggregate(outcomes);
        List<LineResult> lineResults = new ArrayList<>();
        Set<ExpenseCategory> categories = EnumSet.noneOf(ExpenseCategory.class);
        for (LineState ls : st.lines) {
            LineStatus status = !ls.hard.isEmpty() ? LineStatus.INVALID : !ls.flags.isEmpty() ? LineStatus.FLAGGED : LineStatus.VALID;
            List<ReasonCode> reasons = new ArrayList<>(ls.hard);
            ls.flags.forEach(f -> {
                if (!reasons.contains(f)) {
                    reasons.add(f);
                }
            });
            lineResults.add(new LineResult(ls.line.getLineNumber(), status, reasons,
                    ls.policy == null ? null : ls.policy.getExpensePolicyId(), ls.amountReporting,
                    String.join("; ", new LinkedHashSet<>(ls.messages))));
            categories.add(ls.line.getExpenseCategory());
        }
        return new ExpenseEvaluationResult(outcomes, aggregate, lineResults, st.totalReporting, categories, st.split);
    }

    // ------------------------------------------------------------------ claim-level rules

    private RuleOutcome ex01EmployeeEligible(State st) {
        if (st.employee.getEmploymentStatus() == EmploymentStatus.INACTIVE) {
            return RuleOutcome.fail("EX-01", "EMPLOYEE_ELIGIBLE_TO_CLAIM", RuleSeverity.HARD, ReasonCode.INACTIVE_EMPLOYEE,
                    "Employee is inactive and cannot submit expense claims");
        }
        return RuleOutcome.pass("EX-01", "EMPLOYEE_ELIGIBLE_TO_CLAIM",
                "Employee may claim (status " + st.employee.getEmploymentStatus() + ")");
    }

    private RuleOutcome ex02ClaimDates(State st) {
        ExpenseClaim c = st.claim;
        if (c.getExpensePeriodStart() == null || c.getExpensePeriodEnd() == null
                || c.getExpensePeriodEnd().isBefore(c.getExpensePeriodStart())) {
            return RuleOutcome.fail("EX-02", "CLAIM_DATES_VALID", RuleSeverity.HARD, ReasonCode.INVALID_DATE_RANGE,
                    "Expense period is missing or inverted", Map.of("periodStart", String.valueOf(c.getExpensePeriodStart()),
                            "periodEnd", String.valueOf(c.getExpensePeriodEnd())));
        }
        return RuleOutcome.pass("EX-02", "CLAIM_DATES_VALID", "Expense period is valid");
    }

    private RuleOutcome ex03LineCount(State st) {
        int n = st.lines.size();
        int max = properties.expense().maxLinesPerClaim();
        if (n == 0) {
            return RuleOutcome.fail("EX-03", "LINE_ITEM_COUNT", RuleSeverity.HARD, ReasonCode.NO_LINE_ITEMS,
                    "The claim has no line items");
        }
        if (n > max) {
            return RuleOutcome.fail("EX-03", "LINE_ITEM_COUNT", RuleSeverity.HARD, ReasonCode.TOO_MANY_LINE_ITEMS,
                    "The claim has " + n + " line items; the maximum is " + max, Map.of("lineCount", n, "maximum", max));
        }
        return RuleOutcome.pass("EX-03", "LINE_ITEM_COUNT", n + " line item(s)");
    }

    private RuleOutcome ex04Currency(State st) {
        String countryCurrency = countries.findById(st.employee.getCountry()).map(Country::getCurrencyCode).orElse(null);
        boolean supported = currencies.isSupported(st.claim.getCurrency());
        boolean matchesCountry = st.claim.getCurrency().equals(countryCurrency);
        List<Integer> mismatched = new ArrayList<>();
        for (LineState ls : st.lines) {
            if (!ls.line.getCurrency().equals(st.claim.getCurrency()) || !currencies.isSupported(ls.line.getCurrency())) {
                mismatched.add(ls.line.getLineNumber());
                ls.hard(ReasonCode.INVALID_CURRENCY, "Line currency " + ls.line.getCurrency() + " is unsupported or differs from the claim currency");
            }
        }
        st.currencyOk = supported && matchesCountry && mismatched.isEmpty();
        if (st.currencyOk) {
            return RuleOutcome.pass("EX-04", "CURRENCY_VALID", "All amounts are in " + st.claim.getCurrency());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("claimCurrency", st.claim.getCurrency());
        details.put("employeeCountryCurrency", countryCurrency);
        details.put("claimCurrencySupported", supported);
        details.put("mismatchedLines", mismatched);
        return RuleOutcome.fail("EX-04", "CURRENCY_VALID", RuleSeverity.HARD, ReasonCode.INVALID_CURRENCY,
                "Currency is unsupported or inconsistent with the claim / the employee's country", details);
    }

    private RuleOutcome ex05Amounts(State st) {
        List<Integer> bad = new ArrayList<>();
        for (LineState ls : st.lines) {
            if (ls.line.getAmount() == null || ls.line.getAmount().signum() <= 0) {
                bad.add(ls.line.getLineNumber());
                ls.hard(ReasonCode.INVALID_AMOUNT, "Amount must be greater than zero");
            }
        }
        return bad.isEmpty() ? RuleOutcome.pass("EX-05", "AMOUNT_VALID", "All amounts are positive")
                : RuleOutcome.fail("EX-05", "AMOUNT_VALID", RuleSeverity.HARD, ReasonCode.INVALID_AMOUNT,
                "Lines " + bad + " have a missing, zero or negative amount", Map.of("lines", bad));
    }

    private RuleOutcome ex06TransactionDates(State st) {
        ExpenseClaim c = st.claim;
        List<Integer> bad = new ArrayList<>();
        for (LineState ls : st.lines) {
            LocalDate d = ls.line.getTransactionDate();
            boolean outside = d == null
                    || (c.getExpensePeriodStart() != null && d.isBefore(c.getExpensePeriodStart()))
                    || (c.getExpensePeriodEnd() != null && d.isAfter(c.getExpensePeriodEnd()))
                    || d.isAfter(c.getSubmissionDate());
            if (outside) {
                bad.add(ls.line.getLineNumber());
                ls.hard(ReasonCode.INVALID_DATE_RANGE, "Transaction date is outside the claim period or in the future");
            }
        }
        return bad.isEmpty() ? RuleOutcome.pass("EX-06", "TRANSACTION_DATES_VALID", "All transaction dates are valid")
                : RuleOutcome.fail("EX-06", "TRANSACTION_DATES_VALID", RuleSeverity.HARD, ReasonCode.INVALID_DATE_RANGE,
                "Lines " + bad + " are dated outside the expense period or after submission", Map.of("lines", bad));
    }

    private RuleOutcome ex07EmploymentPeriod(State st) {
        List<Integer> bad = new ArrayList<>();
        for (LineState ls : st.lines) {
            LocalDate d = ls.line.getTransactionDate();
            if (d != null && !EmployeeRules.employedOn(st.employee, d)) {
                bad.add(ls.line.getLineNumber());
                ls.hard(ReasonCode.OUTSIDE_EMPLOYMENT_PERIOD, "Transaction " + d + " is outside employment ("
                        + st.employee.getJoiningDate() + " to " + st.employee.getTerminationDate() + ")");
            }
        }
        if (bad.isEmpty()) {
            return RuleOutcome.pass("EX-07", "EMPLOYMENT_PERIOD", "All transactions fall inside the employment period");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("lines", bad);
        details.put("joiningDate", st.employee.getJoiningDate().toString());
        details.put("terminationDate", String.valueOf(st.employee.getTerminationDate()));
        return RuleOutcome.fail("EX-07", "EMPLOYMENT_PERIOD", RuleSeverity.HARD, ReasonCode.OUTSIDE_EMPLOYMENT_PERIOD,
                "Lines " + bad + " are dated outside the employee's employment period", details);
    }

    private RuleOutcome ex08PolicyApplicable(State st) {
        List<Map<String, Object>> failures = new ArrayList<>();
        ReasonCode code = null;
        for (LineState ls : st.lines) {
            ExpenseLine l = ls.line;
            if (l.getTransactionDate() == null) {
                continue;
            }
            Optional<ExpensePolicy> policy = policyResolver.resolve(l.getExpenseCategory(), st.employee.getCountry(),
                    st.employee.getEmploymentType(), l.getTransactionDate());
            if (policy.isPresent()) {
                ls.policy = policy.get();
                continue;
            }
            boolean any = policyResolver.existsAnyVersion(l.getExpenseCategory(), st.employee.getCountry(), st.employee.getEmploymentType());
            ReasonCode lineCode = any ? ReasonCode.POLICY_NOT_EFFECTIVE : ReasonCode.NO_APPLICABLE_POLICY;
            code = code == null ? lineCode : code;
            ls.hard(lineCode, any ? "A policy exists but is not effective on " + l.getTransactionDate()
                    : "No policy for " + l.getExpenseCategory() + " in " + st.employee.getCountry());
            failures.add(Map.of("line", l.getLineNumber(), "reason", lineCode.name(), "category", l.getExpenseCategory().name(),
                    "transactionDate", l.getTransactionDate().toString()));
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-08", "POLICY_APPLICABLE", "An effective policy applies to every line")
                : RuleOutcome.fail("EX-08", "POLICY_APPLICABLE", RuleSeverity.HARD, code,
                "No effective policy applies to " + failures.size() + " line(s)", Map.of("failures", failures));
    }

    // ------------------------------------------------------------------ limits

    private RuleOutcome ex09PerTransactionLimit(State st) {
        if (!st.currencyOk) {
            return RuleOutcome.skipped("EX-09", "PER_TRANSACTION_LIMIT", "Skipped: currency is invalid");
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (LineState ls : st.lines) {
            if (ls.policy != null && ls.line.getAmount().compareTo(ls.policy.getMaximumPerTransaction()) > 0) {
                ls.hard(ReasonCode.POLICY_LIMIT_EXCEEDED, "Amount " + ls.line.getAmount() + " exceeds the per-transaction limit "
                        + ls.policy.getMaximumPerTransaction());
                failures.add(Map.of("line", ls.line.getLineNumber(), "limitType", "PER_TRANSACTION",
                        "amount", ls.line.getAmount(), "limit", ls.policy.getMaximumPerTransaction(),
                        "policy", ls.policy.getExpensePolicyId()));
            }
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-09", "PER_TRANSACTION_LIMIT", "All lines are within the per-transaction limit")
                : RuleOutcome.fail("EX-09", "PER_TRANSACTION_LIMIT", RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED,
                failures.size() + " line(s) exceed the per-transaction limit", Map.of("failures", failures));
    }

    private RuleOutcome ex10DailyLimit(State st) {
        if (!st.currencyOk) {
            return RuleOutcome.skipped("EX-10", "DAILY_LIMIT", "Skipped: currency is invalid");
        }
        Map<String, List<LineState>> groups = new LinkedHashMap<>();
        for (LineState ls : st.lines) {
            if (ls.policy != null && ls.line.getTransactionDate() != null && ls.line.getAmount().signum() > 0) {
                groups.computeIfAbsent(ls.line.getExpenseCategory() + "|" + ls.line.getTransactionDate(), k -> new ArrayList<>()).add(ls);
            }
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (List<LineState> group : groups.values()) {
            ExpensePolicy policy = group.get(0).policy;
            LocalDate day = group.get(0).line.getTransactionDate();
            BigDecimal own = sum(group);
            BigDecimal committed = lineRepository.sumCommitted(st.employee.getEmployeeId(), st.claim.getExpenseClaimId(),
                    COMMITTED_STATUSES, group.get(0).line.getExpenseCategory(), st.claim.getCurrency(), day, day);
            BigDecimal total = Money.scale(own.add(committed));
            if (total.compareTo(policy.getMaximumDailyAmount()) > 0) {
                group.forEach(ls -> ls.hard(ReasonCode.POLICY_LIMIT_EXCEEDED, "Daily total " + total + " exceeds the daily limit "
                        + policy.getMaximumDailyAmount()));
                failures.add(Map.of("limitType", "DAILY", "date", day.toString(), "category", group.get(0).line.getExpenseCategory().name(),
                        "thisClaim", own, "alreadyCommitted", Money.scale(committed), "total", total,
                        "limit", policy.getMaximumDailyAmount()));
            }
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-10", "DAILY_LIMIT", "Daily totals are within the policy limit")
                : RuleOutcome.fail("EX-10", "DAILY_LIMIT", RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED,
                "Daily limit exceeded on " + failures.size() + " day(s)", Map.of("failures", failures));
    }

    private RuleOutcome ex11MonthlyLimit(State st) {
        if (!st.currencyOk) {
            return RuleOutcome.skipped("EX-11", "MONTHLY_LIMIT", "Skipped: currency is invalid");
        }
        Map<String, List<LineState>> groups = new LinkedHashMap<>();
        for (LineState ls : st.lines) {
            if (ls.policy != null && ls.line.getTransactionDate() != null && ls.line.getAmount().signum() > 0) {
                groups.computeIfAbsent(ls.line.getExpenseCategory() + "|" + YearMonth.from(ls.line.getTransactionDate()),
                        k -> new ArrayList<>()).add(ls);
            }
        }
        List<Map<String, Object>> failures = new ArrayList<>();
        for (List<LineState> group : groups.values()) {
            ExpensePolicy policy = group.get(0).policy;
            YearMonth month = YearMonth.from(group.get(0).line.getTransactionDate());
            BigDecimal own = sum(group);
            BigDecimal committed = lineRepository.sumCommitted(st.employee.getEmployeeId(), st.claim.getExpenseClaimId(),
                    COMMITTED_STATUSES, group.get(0).line.getExpenseCategory(), st.claim.getCurrency(),
                    month.atDay(1), month.atEndOfMonth());
            BigDecimal total = Money.scale(own.add(committed));
            if (total.compareTo(policy.getMaximumMonthlyAmount()) > 0) {
                group.forEach(ls -> ls.hard(ReasonCode.POLICY_LIMIT_EXCEEDED, "Monthly total " + total + " exceeds the monthly limit "
                        + policy.getMaximumMonthlyAmount()));
                failures.add(Map.of("limitType", "MONTHLY", "month", month.toString(), "category", group.get(0).line.getExpenseCategory().name(),
                        "thisClaim", own, "alreadyCommitted", Money.scale(committed), "total", total,
                        "limit", policy.getMaximumMonthlyAmount()));
            }
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-11", "MONTHLY_LIMIT", "Monthly totals are within the policy limit")
                : RuleOutcome.fail("EX-11", "MONTHLY_LIMIT", RuleSeverity.HARD, ReasonCode.POLICY_LIMIT_EXCEEDED,
                "Monthly limit exceeded in " + failures.size() + " period(s)", Map.of("failures", failures));
    }

    // ------------------------------------------------------------------ receipts and duplicates

    private RuleOutcome ex12Receipts(State st) {
        List<Map<String, Object>> failures = new ArrayList<>();
        ReasonCode first = null;
        for (LineState ls : st.lines) {
            ExpensePolicy p = ls.policy;
            ExpenseLine l = ls.line;
            if (p == null || !p.isReceiptRequired() || l.getAmount().compareTo(p.getReceiptThreshold()) <= 0) {
                continue;
            }
            ReasonCode code = null;
            String message = null;
            if (l.getReceiptId() == null) {
                boolean badRef = l.getSubmittedReceiptRef() != null && !l.getSubmittedReceiptRef().isBlank();
                code = badRef ? ReasonCode.INVALID_RECEIPT_REFERENCE : ReasonCode.RECEIPT_MISSING;
                message = badRef ? "Receipt reference '" + l.getSubmittedReceiptRef() + "' does not exist"
                        : "A receipt is required above " + p.getReceiptThreshold() + " but none was provided";
            } else {
                Optional<Receipt> receipt = receipts.findById(l.getReceiptId());
                ReceiptValidator.Result result = receipt.map(receiptValidator::validate)
                        .orElse(new ReceiptValidator.Result(false, "Receipt record not found"));
                if (!result.valid()) {
                    code = ReasonCode.RECEIPT_INVALID;
                    message = result.reason();
                }
            }
            if (code != null) {
                ls.flag(code, message);
                first = first == null ? code : first;
                failures.add(Map.of("line", l.getLineNumber(), "reason", code.name(), "message", message));
            }
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-12", "RECEIPT_REQUIREMENT", "Receipt requirements are satisfied")
                : RuleOutcome.fail("EX-12", "RECEIPT_REQUIREMENT", RuleSeverity.FLAG, first,
                failures.size() + " line(s) fail the receipt requirement", Map.of("failures", failures));
    }

    private RuleOutcome ex13DuplicateReceipt(State st) {
        List<Map<String, Object>> failures = new ArrayList<>();
        for (LineState ls : st.lines) {
            if (ls.line.getReceiptId() == null) {
                continue;
            }
            Optional<Receipt> receipt = receipts.findById(ls.line.getReceiptId());
            if (receipt.isEmpty()) {
                continue;
            }
            List<ExpenseLine> others = lineRepository.findLinesUsingReceiptHash(receipt.get().getReceiptHash(),
                    st.claim.getExpenseClaimId(), DEAD_STATUSES);
            if (!others.isEmpty()) {
                List<String> otherLines = others.stream().map(ExpenseLine::getExpenseLineId).toList();
                ls.flag(ReasonCode.DUPLICATE_RECEIPT, "Receipt already used on " + otherLines);
                failures.add(Map.of("line", ls.line.getLineNumber(), "receiptHash", receipt.get().getReceiptHash(),
                        "alsoUsedOn", otherLines));
            }
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-13", "NO_DUPLICATE_RECEIPT", "No receipt is reused on another claim")
                : RuleOutcome.fail("EX-13", "NO_DUPLICATE_RECEIPT", RuleSeverity.FLAG, ReasonCode.DUPLICATE_RECEIPT,
                failures.size() + " line(s) reuse a receipt already attached elsewhere", Map.of("failures", failures));
    }

    private RuleOutcome ex14DuplicateSubmission(State st) {
        List<Map<String, Object>> failures = new ArrayList<>();
        for (LineState ls : st.lines) {
            ExpenseLine l = ls.line;
            if (l.getTransactionDate() == null || l.getAmount().signum() <= 0) {
                continue;
            }
            List<ExpenseLine> dups = lineRepository.findExactDuplicates(st.employee.getEmployeeId(), st.claim.getExpenseClaimId(),
                    DEAD_STATUSES, l.getMerchantName().toLowerCase(java.util.Locale.ROOT), l.getTransactionDate(), l.getAmount(),
                    l.getExpenseCategory());
            if (!dups.isEmpty()) {
                List<String> ids = dups.stream().map(ExpenseLine::getExpenseLineId).toList();
                ls.flag(ReasonCode.DUPLICATE_SUBMISSION, "Identical line already submitted: " + ids);
                failures.add(Map.of("line", l.getLineNumber(), "duplicateOf", ids));
            }
        }
        return failures.isEmpty() ? RuleOutcome.pass("EX-14", "NO_DUPLICATE_SUBMISSION", "No identical line was submitted before")
                : RuleOutcome.fail("EX-14", "NO_DUPLICATE_SUBMISSION", RuleSeverity.FLAG, ReasonCode.DUPLICATE_SUBMISSION,
                failures.size() + " line(s) duplicate an earlier submission", Map.of("failures", failures));
    }

    // ------------------------------------------------------------------ cost center and budget

    private RuleOutcome ex15CostCenterValid(State st) {
        String ccId = st.claim.getCostCenterId();
        if (ccId == null) {
            st.costCenterValid = false;
            return RuleOutcome.fail("EX-15", "COST_CENTER_VALID", RuleSeverity.HOLD, ReasonCode.INVALID_COST_CENTER,
                    "Cost center is missing or unknown (submitted value: '" + st.claim.getSubmittedCostCenterRef() + "')",
                    Map.of("submittedCostCenterRef", String.valueOf(st.claim.getSubmittedCostCenterRef())));
        }
        Optional<CostCenter> cc = costCenters.findById(ccId);
        if (cc.isEmpty() || !cc.get().isActive()) {
            st.costCenterValid = false;
            return RuleOutcome.fail("EX-15", "COST_CENTER_VALID", RuleSeverity.HOLD, ReasonCode.INVALID_COST_CENTER,
                    "Cost center " + ccId + " is not active", Map.of("costCenterId", ccId,
                            "status", cc.map(c -> c.getStatus().name()).orElse("NOT_FOUND")));
        }
        st.costCenterValid = true;
        return RuleOutcome.pass("EX-15", "COST_CENTER_VALID", "Cost center " + ccId + " is active");
    }

    private RuleOutcome ex16CostCenterAuthorized(State st) {
        if (!st.costCenterValid) {
            return RuleOutcome.skipped("EX-16", "COST_CENTER_AUTHORIZED", "Skipped: cost center is not valid");
        }
        boolean authorized = assignments.findEffective(st.employee.getEmployeeId(), st.claim.getClaimDate()).stream()
                .anyMatch(a -> a.getCostCenterId().equals(st.claim.getCostCenterId()));
        if (authorized) {
            return RuleOutcome.pass("EX-16", "COST_CENTER_AUTHORIZED", "Employee is assigned to the cost center");
        }
        return RuleOutcome.fail("EX-16", "COST_CENTER_AUTHORIZED", RuleSeverity.FLAG, ReasonCode.UNAUTHORIZED_COST_CENTER,
                "Employee has no effective assignment to " + st.claim.getCostCenterId() + " on " + st.claim.getClaimDate(),
                Map.of("costCenterId", st.claim.getCostCenterId(), "claimDate", st.claim.getClaimDate().toString()));
    }

    private RuleOutcome ex17Budget(State st) {
        if (!st.costCenterValid || !st.currencyOk || st.totalReporting == null) {
            return RuleOutcome.skipped("EX-17", "BUDGET_AVAILABLE", "Skipped: cost center or currency is not valid");
        }
        var position = budgets.position(st.claim.getCostCenterId(), st.claim.getClaimDate(), st.claim.getExpenseClaimId());
        if (position.isEmpty()) {
            return RuleOutcome.fail("EX-17", "BUDGET_AVAILABLE", RuleSeverity.HOLD, ReasonCode.INSUFFICIENT_BUDGET,
                    "No active budget exists for " + st.claim.getCostCenterId() + " on " + st.claim.getClaimDate());
        }
        var p = position.get();
        BigDecimal claimInBudgetCurrency = currencies.convert(st.claim.getTotalAmount(), st.claim.getCurrency(), p.currency());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("budgetId", p.budgetId());
        details.put("currency", p.currency());
        details.put("allocated", p.allocated());
        details.put("approvedSpend", p.approvedSpend());
        details.put("pendingCommitted", p.pendingCommitted());
        details.put("remaining", p.remaining());
        details.put("claimAmount", claimInBudgetCurrency);
        if (claimInBudgetCurrency.compareTo(p.remaining()) <= 0) {
            return RuleOutcome.pass("EX-17", "BUDGET_AVAILABLE", "Remaining budget " + p.remaining() + " covers the claim", details);
        }
        return RuleOutcome.fail("EX-17", "BUDGET_AVAILABLE", RuleSeverity.HOLD, ReasonCode.INSUFFICIENT_BUDGET,
                "Remaining budget " + p.remaining() + " " + p.currency() + " is lower than the claim " + claimInBudgetCurrency, details);
    }

    // ------------------------------------------------------------------ split claims

    private RuleOutcome ex18SplitClaim(State st) {
        if (!st.costCenterValid || !st.currencyOk || st.totalReporting == null) {
            return RuleOutcome.skipped("EX-18", "NO_SPLIT_CLAIM", "Skipped: cost center or currency is not valid");
        }
        List<BigDecimal> boundaries = thresholds.expenseBoundaries(st.claim.getClaimDate());
        int window = properties.expense().splitWindowDays();
        Map<String, List<LineState>> groups = new LinkedHashMap<>();
        for (LineState ls : st.lines) {
            if (ls.amountReporting != null && ls.line.getTransactionDate() != null) {
                groups.computeIfAbsent(SplitClaimDetector.merchantKey(ls.line.getMerchantName()) + "|" + ls.line.getExpenseCategory(),
                        k -> new ArrayList<>()).add(ls);
            }
        }
        for (List<LineState> group : groups.values()) {
            ExpenseLine first = group.get(0).line;
            LocalDate min = group.stream().map(g -> g.line.getTransactionDate()).min(LocalDate::compareTo).orElseThrow();
            LocalDate max = group.stream().map(g -> g.line.getTransactionDate()).max(LocalDate::compareTo).orElseThrow();
            List<ExpenseLine> candidateLines = chainedSplitCandidates(st, first, min, max, window);
            List<SplitClaimDetector.RelatedLine> candidates = new ArrayList<>();
            String key = SplitClaimDetector.merchantKey(first.getMerchantName());
            for (ExpenseLine c : candidateLines) {
                if (!SplitClaimDetector.merchantKey(c.getMerchantName()).equals(key) || !currencies.isSupported(c.getCurrency())
                        || c.getAmount().signum() <= 0 || !currencies.isSupported(c.getClaim().getCurrency())) {
                    continue;
                }
                candidates.add(new SplitClaimDetector.RelatedLine(c.getClaim().getExpenseClaimId(), c.getExpenseLineId(),
                        c.getTransactionDate(), currencies.toReporting(c.getAmount(), c.getCurrency()),
                        currencies.toReporting(c.getClaim().getTotalAmount(), c.getClaim().getCurrency()), c.getDescription()));
            }
            List<SplitClaimDetector.RelatedLine> own = group.stream().map(g -> new SplitClaimDetector.RelatedLine(
                    st.claim.getExpenseClaimId(), g.line.getExpenseLineId(), g.line.getTransactionDate(), g.amountReporting,
                    st.totalReporting, g.line.getDescription())).toList();
            Optional<SplitClaimDetector.Finding> finding =
                    SplitClaimDetector.detect(st.claim.getExpenseClaimId(), own, candidates, boundaries, window);
            if (finding.isPresent()) {
                st.split = finding;
                var f = finding.get();
                group.forEach(g -> g.flag(ReasonCode.SUSPICIOUS_SPLIT_CLAIM, "Related claims " + f.relatedClaimIds()
                        + " total " + f.combinedReporting() + " USD against boundary " + f.boundary()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("relatedClaimIds", f.relatedClaimIds());
                details.put("relatedLineIds", f.relatedLineIds());
                details.put("combinedAmountUsd", f.combinedReporting());
                details.put("boundaryUsd", f.boundary());
                details.put("windowDays", f.windowDays());
                details.put("maxDescriptionSimilarity", f.maxDescriptionSimilarity());
                return RuleOutcome.fail("EX-18", "NO_SPLIT_CLAIM", RuleSeverity.FLAG, ReasonCode.SUSPICIOUS_SPLIT_CLAIM,
                        "Claims " + f.relatedClaimIds() + " are individually within " + f.boundary()
                                + " USD but together total " + f.combinedReporting() + " USD", details);
            }
        }
        return RuleOutcome.pass("EX-18", "NO_SPLIT_CLAIM", "No related claims combine to cross an approval threshold");
    }

    /** Upper bound on how many times the search range is widened while following a chain of related claims. */
    private static final int MAX_CHAIN_ROUNDS = 12;

    /**
     * Candidate lines of other claims that could belong to a split pattern with this claim. A chain is followed
     * transitively: when a candidate lies near the edge of the searched date range, the range is widened by the
     * window around it and the query is repeated, until no further line is found (or {@link #MAX_CHAIN_ROUNDS} is
     * reached). Claims A (day 0), B (day 1) and C (day 4) with a 3-day window are therefore one chain even though
     * A and C are four days apart; {@link SplitClaimDetector} then decides which candidates are actually linked.
     */
    private List<ExpenseLine> chainedSplitCandidates(State st, ExpenseLine first, LocalDate min, LocalDate max, int window) {
        LocalDate from = min.minusDays(window);
        LocalDate to = max.plusDays(window);
        String merchantLower = first.getMerchantName().toLowerCase(java.util.Locale.ROOT);
        List<ExpenseLine> found = List.of();
        for (int round = 0; round < MAX_CHAIN_ROUNDS; round++) {
            found = lineRepository.findSplitCandidates(st.employee.getEmployeeId(), st.claim.getExpenseClaimId(), DEAD_STATUSES,
                    st.claim.getCostCenterId(), merchantLower, first.getExpenseCategory(), from, to);
            LocalDate widenedFrom = from;
            LocalDate widenedTo = to;
            for (ExpenseLine candidate : found) {
                LocalDate date = candidate.getTransactionDate();
                if (date.minusDays(window).isBefore(widenedFrom)) {
                    widenedFrom = date.minusDays(window);
                }
                if (date.plusDays(window).isAfter(widenedTo)) {
                    widenedTo = date.plusDays(window);
                }
            }
            if (widenedFrom.equals(from) && widenedTo.equals(to)) {
                break;
            }
            from = widenedFrom;
            to = widenedTo;
        }
        return found;
    }

    private static BigDecimal sum(List<LineState> group) {
        BigDecimal total = Money.ZERO;
        for (LineState ls : group) {
            total = total.add(ls.line.getAmount());
        }
        return Money.scale(total);
    }

    /** Exposed for tests: the distinct categories of a set of lines. */
    static Set<ExpenseCategory> categoriesOf(List<ExpenseLine> lines) {
        Set<ExpenseCategory> out = EnumSet.noneOf(ExpenseCategory.class);
        lines.forEach(l -> out.add(l.getExpenseCategory()));
        return out;
    }
}
