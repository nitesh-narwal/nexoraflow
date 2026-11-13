package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.approval.ApprovalThresholds;
import me.niteshh.nexoraflow.budget.BudgetService;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.configuration.Country;
import me.niteshh.nexoraflow.configuration.CountryRepository;
import me.niteshh.nexoraflow.configuration.Currency;
import me.niteshh.nexoraflow.configuration.CurrencyRepository;
import me.niteshh.nexoraflow.configuration.CurrencyService;
import me.niteshh.nexoraflow.configuration.NexoraProperties;
import me.niteshh.nexoraflow.costcenter.CostCenter;
import me.niteshh.nexoraflow.costcenter.CostCenterRepository;
import me.niteshh.nexoraflow.costcenter.CostCenterStatus;
import me.niteshh.nexoraflow.employee.AssignmentType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeCostCenter;
import me.niteshh.nexoraflow.employee.EmployeeCostCenterRepository;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.expense.ExpenseClaim;
import me.niteshh.nexoraflow.expense.ExpenseEvaluationEngine;
import me.niteshh.nexoraflow.expense.ExpenseEvaluationResult;
import me.niteshh.nexoraflow.expense.ExpenseLine;
import me.niteshh.nexoraflow.expense.ExpenseLineRepository;
import me.niteshh.nexoraflow.expense.ExpensePolicyResolver;
import me.niteshh.nexoraflow.expense.LineStatus;
import me.niteshh.nexoraflow.expense.Receipt;
import me.niteshh.nexoraflow.expense.ReceiptRepository;
import me.niteshh.nexoraflow.expense.ReceiptValidator;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.ExpensePolicyRepository;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;

/** Expense rules with repositories mocked: limits, receipts, budget, cost center, employment period. */
class ExpenseEvaluationEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private ExpenseLineRepository lines;
    private ReceiptRepository receipts;
    private ExpensePolicyRepository policies;
    private CostCenterRepository costCenters;
    private EmployeeCostCenterRepository assignments;
    private BudgetService budgets;
    private ApprovalThresholds thresholds;
    private ExpenseEvaluationEngine engine;
    private Employee employee;

    @BeforeEach
    void setUp() {
        lines = mock(ExpenseLineRepository.class);
        receipts = mock(ReceiptRepository.class);
        policies = mock(ExpensePolicyRepository.class);
        costCenters = mock(CostCenterRepository.class);
        assignments = mock(EmployeeCostCenterRepository.class);
        budgets = mock(BudgetService.class);
        thresholds = mock(ApprovalThresholds.class);
        when(thresholds.expenseBoundaries(any())).thenReturn(List.of(new BigDecimal("500"), new BigDecimal("2000"), new BigDecimal("5000")));

        CurrencyRepository currencies = mock(CurrencyRepository.class);
        Currency usd = new Currency();
        usd.setCurrencyCode("USD");
        usd.setRateToReporting(BigDecimal.ONE);
        usd.setSupported(true);
        when(currencies.findById("USD")).thenReturn(Optional.of(usd));
        CountryRepository countries = mock(CountryRepository.class);
        Country us = new Country();
        us.setCountryCode("US");
        us.setCurrencyCode("USD");
        when(countries.findById("US")).thenReturn(Optional.of(us));

        ExpensePolicy meals = new ExpensePolicy();
        meals.setExpensePolicyId("XPOL-MEALS-US");
        meals.setExpenseCategory(ExpenseCategory.MEALS);
        meals.setCountry("US");
        meals.setEmployeeType(PolicyEmployeeType.ALL);
        meals.setCurrency("USD");
        meals.setMaximumPerTransaction(new BigDecimal("75"));
        meals.setMaximumDailyAmount(new BigDecimal("120"));
        meals.setMaximumMonthlyAmount(new BigDecimal("1000"));
        meals.setReceiptRequired(true);
        meals.setReceiptThreshold(new BigDecimal("25"));
        meals.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        when(policies.findByExpenseCategoryAndCountryOrderByEffectiveFromDesc(ExpenseCategory.MEALS, "US")).thenReturn(List.of(meals));
        when(policies.findByExpenseCategoryAndCountryOrderByEffectiveFromDesc(ExpenseCategory.TRAINING, "US")).thenReturn(List.of());

        CostCenter cc = new CostCenter();
        cc.setCostCenterId("CC-0001");
        cc.setStatus(CostCenterStatus.ACTIVE);
        when(costCenters.findById("CC-0001")).thenReturn(Optional.of(cc));
        EmployeeCostCenter assignment = new EmployeeCostCenter();
        assignment.setCostCenterId("CC-0001");
        assignment.setAssignmentType(AssignmentType.PRIMARY);
        when(assignments.findEffective(anyString(), any())).thenReturn(List.of(assignment));
        when(budgets.position(anyString(), any(), any())).thenReturn(Optional.of(new BudgetService.BudgetPosition("B1", "CC-0001",
                TODAY.withDayOfMonth(1), TODAY.withDayOfMonth(31), "USD", new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10000"))));
        when(lines.sumCommitted(anyString(), anyString(), any(), any(), anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(lines.findExactDuplicates(anyString(), anyString(), any(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(lines.findSplitCandidates(anyString(), anyString(), any(), anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(lines.findLinesUsingReceiptHash(anyString(), anyString(), any())).thenReturn(List.of());

        engine = new ExpenseEvaluationEngine(new NexoraProperties("BR-TEST", "USD", null, null, null), new CurrencyService(currencies), countries,
                new ExpensePolicyResolver(policies), receipts, new ReceiptValidator("PDF,JPG,PNG", 1_000_000L), lines, costCenters,
                assignments, budgets, thresholds);

        employee = new Employee();
        employee.setEmployeeId("EMP-000008");
        employee.setCountry("US");
        employee.setEmploymentType(EmploymentType.FULL_TIME);
        employee.setEmploymentStatus(EmploymentStatus.ACTIVE);
        employee.setJoiningDate(LocalDate.of(2020, 1, 6));
    }

    private ExpenseClaim claim(String amount, String receiptId, LocalDate date, String costCenter) {
        ExpenseClaim c = new ExpenseClaim();
        c.setExpenseClaimId("EXP-2026-000001");
        c.setEmployeeId(employee.getEmployeeId());
        c.setClaimDate(TODAY);
        c.setSubmissionDate(TODAY);
        c.setExpensePeriodStart(date);
        c.setExpensePeriodEnd(date);
        c.setCurrency("USD");
        c.setCostCenterId(costCenter);
        ExpenseLine l = new ExpenseLine();
        l.setExpenseLineId("EXPL-EXP-2026-000001-01");
        l.setLineNumber(1);
        l.setExpenseCategory(ExpenseCategory.MEALS);
        l.setMerchantName("Cafe Roma");
        l.setTransactionDate(date);
        l.setAmount(new BigDecimal(amount));
        l.setCurrency("USD");
        l.setReceiptId(receiptId);
        l.setLineStatus(LineStatus.SUBMITTED);
        c.addLine(l);
        c.recalculateTotal();
        return c;
    }

    private Receipt receipt(String id, String type) {
        Receipt r = new Receipt();
        r.setReceiptId(id);
        r.setFileType(type);
        r.setFileSizeBytes(2000);
        r.setReceiptStatus("VALID");
        r.setReceiptHash("a".repeat(64));
        when(receipts.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private ExpenseEvaluationResult evaluate(ExpenseClaim c) {
        return engine.evaluate(c, employee);
    }

    @Test
    void aCleanClaimPassesEveryRule() {
        receipt("RCP-1", "PDF");
        ExpenseEvaluationResult r = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(r.aggregate().decision()).isEqualTo(Decision.PENDING_APPROVAL);
        assertThat(r.lines().get(0).status()).isEqualTo(LineStatus.VALID);
        assertThat(r.totalReporting()).isEqualByComparingTo("40.00");
    }

    @Test
    void perTransactionLimitIsEnforcedAtTheBoundary() {
        receipt("RCP-1", "PDF");
        assertThat(evaluate(claim("75.00", "RCP-1", TODAY.minusDays(2), "CC-0001")).aggregate().decision()).isEqualTo(Decision.PENDING_APPROVAL);
        ExpenseEvaluationResult over = evaluate(claim("75.01", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(over.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(over.aggregate().reasonCodes()).containsExactly(ReasonCode.POLICY_LIMIT_EXCEEDED);
        assertThat(over.lines().get(0).status()).isEqualTo(LineStatus.INVALID);
    }

    @Test
    void dailyAndMonthlyTotalsIncludeWhatIsAlreadyCommitted() {
        receipt("RCP-1", "PDF");
        when(lines.sumCommitted(eq("EMP-000008"), anyString(), any(), eq(ExpenseCategory.MEALS), eq("USD"),
                eq(TODAY.minusDays(2)), eq(TODAY.minusDays(2)))).thenReturn(new BigDecimal("90.00")); // 90 + 40 > 120 per day
        ExpenseEvaluationResult daily = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(daily.aggregate().reasonCodes()).containsExactly(ReasonCode.POLICY_LIMIT_EXCEEDED);
        assertThat(daily.outcomes().stream().filter(o -> o.failed()).map(o -> o.ruleId())).containsExactly("EX-10");

        when(lines.sumCommitted(anyString(), anyString(), any(), any(), anyString(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(lines.sumCommitted(eq("EMP-000008"), anyString(), any(), eq(ExpenseCategory.MEALS), eq("USD"),
                eq(LocalDate.of(2026, 10, 1)), eq(LocalDate.of(2026, 10, 31)))).thenReturn(new BigDecimal("980.00"));
        ExpenseEvaluationResult monthly = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(monthly.outcomes().stream().filter(o -> o.failed()).map(o -> o.ruleId())).containsExactly("EX-11");
    }

    @Test
    void receiptsAreRequiredAboveTheThresholdAndValidated() {
        ExpenseEvaluationResult missing = evaluate(claim("40.00", null, TODAY.minusDays(2), "CC-0001"));
        assertThat(missing.aggregate().decision()).isEqualTo(Decision.FLAGGED);
        assertThat(missing.aggregate().reasonCodes()).containsExactly(ReasonCode.RECEIPT_MISSING);
        // at or below the threshold no receipt is needed
        assertThat(evaluate(claim("25.00", null, TODAY.minusDays(2), "CC-0001")).aggregate().decision()).isEqualTo(Decision.PENDING_APPROVAL);
        receipt("RCP-2", "EXE");
        assertThat(evaluate(claim("40.00", "RCP-2", TODAY.minusDays(2), "CC-0001")).aggregate().reasonCodes()).containsExactly(ReasonCode.RECEIPT_INVALID);
    }

    @Test
    void reusedReceiptHashesAreFlagged() {
        receipt("RCP-1", "PDF");
        ExpenseLine other = new ExpenseLine();
        other.setExpenseLineId("EXPL-EXP-2026-000099-01");
        when(lines.findLinesUsingReceiptHash(anyString(), anyString(), any())).thenReturn(List.of(other));
        ExpenseEvaluationResult r = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(r.aggregate().decision()).isEqualTo(Decision.FLAGGED);
        assertThat(r.aggregate().reasonCodes()).containsExactly(ReasonCode.DUPLICATE_RECEIPT);
    }

    @Test
    void missingOrClosedCostCentersPutTheClaimOnHold() {
        receipt("RCP-1", "PDF");
        ExpenseClaim none = claim("40.00", "RCP-1", TODAY.minusDays(2), null);
        none.setSubmittedCostCenterRef("CC-9999");
        ExpenseEvaluationResult r = evaluate(none);
        assertThat(r.aggregate().decision()).isEqualTo(Decision.ON_HOLD);
        assertThat(r.aggregate().reasonCodes()).containsExactly(ReasonCode.INVALID_COST_CENTER);
        CostCenter closed = new CostCenter();
        closed.setCostCenterId("CC-0002");
        closed.setStatus(CostCenterStatus.CLOSED);
        when(costCenters.findById("CC-0002")).thenReturn(Optional.of(closed));
        assertThat(evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0002")).aggregate().reasonCodes()).containsExactly(ReasonCode.INVALID_COST_CENTER);
    }

    @Test
    void usingACostCenterTheEmployeeIsNotAssignedToIsFlagged() {
        receipt("RCP-1", "PDF");
        CostCenter other = new CostCenter();
        other.setCostCenterId("CC-0003");
        other.setStatus(CostCenterStatus.ACTIVE);
        when(costCenters.findById("CC-0003")).thenReturn(Optional.of(other));
        ExpenseEvaluationResult r = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0003"));
        assertThat(r.aggregate().decision()).isEqualTo(Decision.FLAGGED);
        assertThat(r.aggregate().reasonCodes()).containsExactly(ReasonCode.UNAUTHORIZED_COST_CENTER);
    }

    @Test
    void insufficientBudgetPutsTheClaimOnHold() {
        receipt("RCP-1", "PDF");
        when(budgets.position(anyString(), any(), any())).thenReturn(Optional.of(new BudgetService.BudgetPosition("B1", "CC-0001",
                TODAY.withDayOfMonth(1), TODAY.withDayOfMonth(31), "USD", new BigDecimal("1000"), new BigDecimal("980"), BigDecimal.ZERO, new BigDecimal("20"))));
        ExpenseEvaluationResult r = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(r.aggregate().decision()).isEqualTo(Decision.ON_HOLD);
        assertThat(r.aggregate().reasonCodes()).containsExactly(ReasonCode.INSUFFICIENT_BUDGET);
    }

    @Test
    void transactionsOutsideTheEmploymentPeriodAreRejected() {
        receipt("RCP-1", "PDF");
        employee.setJoiningDate(LocalDate.of(2026, 6, 1));
        ExpenseEvaluationResult before = evaluate(claim("40.00", "RCP-1", LocalDate.of(2026, 5, 20), "CC-0001"));
        assertThat(before.aggregate().reasonCodes()).containsExactly(ReasonCode.OUTSIDE_EMPLOYMENT_PERIOD);
        employee.setJoiningDate(LocalDate.of(2020, 1, 1));
        employee.setEmploymentStatus(EmploymentStatus.TERMINATED);
        employee.setTerminationDate(LocalDate.of(2026, 5, 31));
        ExpenseEvaluationResult after = evaluate(claim("40.00", "RCP-1", LocalDate.of(2026, 7, 1), "CC-0001"));
        assertThat(after.aggregate().reasonCodes()).containsExactly(ReasonCode.OUTSIDE_EMPLOYMENT_PERIOD);
    }

    @Test
    void noApplicablePolicyAndNotYetEffectivePolicyAreDistinguished() {
        receipt("RCP-1", "PDF");
        ExpenseClaim training = claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001");
        training.getLines().get(0).setExpenseCategory(ExpenseCategory.TRAINING);
        assertThat(evaluate(training).aggregate().reasonCodes()).containsExactly(ReasonCode.NO_APPLICABLE_POLICY);
        // the MEALS policy only starts on 2026-01-01, so a December 2025 transaction has a policy that is not effective yet
        ExpenseEvaluationResult old = evaluate(claim("40.00", "RCP-1", LocalDate.of(2025, 12, 15), "CC-0001"));
        assertThat(old.aggregate().reasonCodes()).containsExactly(ReasonCode.POLICY_NOT_EFFECTIVE);
    }

    @Test
    void claimTotalIsCalculatedFromLinesNeverSetIndependently() {
        ExpenseClaim c = claim("40.00", null, TODAY.minusDays(2), "CC-0001");
        ExpenseLine second = new ExpenseLine();
        second.setAmount(new BigDecimal("10.25"));
        ExpenseLine negative = new ExpenseLine();
        negative.setAmount(new BigDecimal("-5.00"));
        c.addLine(second);
        c.addLine(negative);
        c.recalculateTotal();
        assertThat(c.getTotalAmount()).isEqualByComparingTo("50.25"); // only positive lines make up the total
        assertThat(c.sumOfLines()).isEqualByComparingTo(c.getTotalAmount());
    }

    private static List<String> failedRules(ExpenseEvaluationResult r) {
        return r.outcomes().stream().filter(o -> o.failed()).map(o -> o.ruleId()).toList();
    }

    /** Pads the claim with identical small MEALS lines until it has {@code total} lines. */
    private static void addMealLines(ExpenseClaim c, int total) {
        for (int n = c.getLines().size() + 1; n <= total; n++) {
            ExpenseLine l = new ExpenseLine();
            l.setExpenseLineId(String.format("EXPL-EXP-2026-000001-%02d", n));
            l.setLineNumber(n);
            l.setExpenseCategory(ExpenseCategory.MEALS);
            l.setMerchantName("Cafe Roma");
            l.setTransactionDate(c.getExpensePeriodStart());
            l.setAmount(new BigDecimal("5.00"));
            l.setCurrency("USD");
            l.setLineStatus(LineStatus.SUBMITTED);
            c.addLine(l);
        }
        c.recalculateTotal();
    }

    @Test
    void aClaimNeedsBetweenOneAndTheConfiguredMaximumNumberOfLines() { // EX-03
        LocalDate day = TODAY.minusDays(2);
        ExpenseClaim ten = claim("5.00", null, day, "CC-0001");
        addMealLines(ten, 10);
        assertThat(evaluate(ten).aggregate().decision()).isEqualTo(Decision.PENDING_APPROVAL);

        ExpenseClaim eleven = claim("5.00", null, day, "CC-0001");
        addMealLines(eleven, 11);
        ExpenseEvaluationResult tooMany = evaluate(eleven);
        assertThat(tooMany.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(tooMany.aggregate().reasonCodes()).containsExactly(ReasonCode.TOO_MANY_LINE_ITEMS);
        assertThat(failedRules(tooMany)).containsExactly("EX-03");

        ExpenseClaim empty = claim("5.00", null, day, "CC-0001");
        empty.getLines().clear();
        empty.recalculateTotal();
        ExpenseEvaluationResult none = evaluate(empty);
        assertThat(none.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(none.aggregate().reasonCodes()).containsExactly(ReasonCode.NO_LINE_ITEMS);
        assertThat(failedRules(none)).containsExactly("EX-03");
    }

    @Test
    void inactiveEmployeesCannotClaimButTerminatedEmployeesMayClaimForTheirEmploymentPeriod() { // EX-01 / EX-07
        receipt("RCP-1", "PDF");
        employee.setEmploymentStatus(EmploymentStatus.INACTIVE);
        ExpenseEvaluationResult inactive = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(inactive.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(inactive.aggregate().reasonCodes()).containsExactly(ReasonCode.INACTIVE_EMPLOYEE);
        assertThat(failedRules(inactive)).containsExactly("EX-01");

        employee.setEmploymentStatus(EmploymentStatus.TERMINATED);
        employee.setTerminationDate(TODAY.minusDays(1));
        assertThat(evaluate(claim("40.00", "RCP-1", TODAY.minusDays(20), "CC-0001")).aggregate().decision())
                .isEqualTo(Decision.PENDING_APPROVAL);
    }

    @Test
    void invertedPeriodsAndTransactionDatesInTheFutureAreRejected() { // EX-02 / EX-06
        receipt("RCP-1", "PDF");
        ExpenseClaim inverted = claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001");
        inverted.setExpensePeriodStart(TODAY);
        inverted.setExpensePeriodEnd(TODAY.minusDays(3));
        ExpenseEvaluationResult r = evaluate(inverted);
        assertThat(r.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(r.aggregate().reasonCodes()).containsExactly(ReasonCode.INVALID_DATE_RANGE);
        assertThat(failedRules(r)).contains("EX-02");

        ExpenseEvaluationResult future = evaluate(claim("40.00", "RCP-1", TODAY.plusDays(1), "CC-0001"));
        assertThat(future.aggregate().reasonCodes()).containsExactly(ReasonCode.INVALID_DATE_RANGE);
        assertThat(failedRules(future)).containsExactly("EX-06");
    }

    @Test
    void amountsMustBePositiveAndTheCurrencyMustBeTheEmployeesCountryCurrency() { // EX-05 / EX-04
        receipt("RCP-1", "PDF");
        ExpenseEvaluationResult zero = evaluate(claim("0.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(zero.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(zero.aggregate().reasonCodes()).containsExactly(ReasonCode.INVALID_AMOUNT);
        assertThat(zero.lines().get(0).status()).isEqualTo(LineStatus.INVALID);

        ExpenseClaim euro = claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001");
        euro.setCurrency("EUR");
        euro.getLines().get(0).setCurrency("EUR");
        ExpenseEvaluationResult e = evaluate(euro);
        assertThat(e.aggregate().decision()).isEqualTo(Decision.REJECTED);
        assertThat(e.aggregate().reasonCodes()).containsExactly(ReasonCode.INVALID_CURRENCY);
        assertThat(failedRules(e)).containsExactly("EX-04");
    }

    /** A line of an earlier, still live claim by the same employee at the same merchant. */
    private ExpenseLine earlierLine(String claimId, LocalDate date, String amount) {
        ExpenseClaim earlier = new ExpenseClaim();
        earlier.setExpenseClaimId(claimId);
        earlier.setEmployeeId(employee.getEmployeeId());
        earlier.setCurrency("USD");
        ExpenseLine l = new ExpenseLine();
        l.setExpenseLineId(claimId.replace("EXP", "EXPL") + "-01");
        l.setLineNumber(1);
        l.setExpenseCategory(ExpenseCategory.MEALS);
        l.setMerchantName("Cafe Roma");
        l.setTransactionDate(date);
        l.setDescription("Cafe Roma meals");
        l.setAmount(new BigDecimal(amount));
        l.setCurrency("USD");
        earlier.addLine(l);
        earlier.recalculateTotal();
        return l;
    }

    @Test
    void aSplitChainIsFollowedEvenWhenItsFirstClaimIsOutsideTheWindowAroundTheNewClaim() { // EX-18
        receipt("RCP-1", "PDF");
        when(thresholds.expenseBoundaries(any())).thenReturn(List.of(new BigDecimal("100")));
        // 3-day window: day -2 (this claim) - day -5 - day -8; the oldest claim is 6 days away from the new one
        List<ExpenseLine> history = List.of(earlierLine("EXP-2026-000090", TODAY.minusDays(8), "40.00"),
                earlierLine("EXP-2026-000091", TODAY.minusDays(5), "40.00"));
        when(lines.findSplitCandidates(anyString(), anyString(), any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(6);
            LocalDate to = call.getArgument(7);
            return history.stream().filter(l -> !l.getTransactionDate().isBefore(from) && !l.getTransactionDate().isAfter(to)).toList();
        });
        ExpenseEvaluationResult r = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(r.aggregate().decision()).isEqualTo(Decision.FLAGGED);
        assertThat(r.aggregate().reasonCodes()).containsExactly(ReasonCode.SUSPICIOUS_SPLIT_CLAIM);
        assertThat(r.splitFinding()).isPresent();
        assertThat(r.splitFinding().get().relatedClaimIds()).containsExactlyInAnyOrder("EXP-2026-000090", "EXP-2026-000091");
        assertThat(r.splitFinding().get().combinedReporting()).isEqualByComparingTo("120.00");
    }

    @Test
    void aClaimThatIsTooFarFromTheChainIsNotLinkedToIt() { // EX-18 negative
        receipt("RCP-1", "PDF");
        when(thresholds.expenseBoundaries(any())).thenReturn(List.of(new BigDecimal("100")));
        List<ExpenseLine> history = List.of(earlierLine("EXP-2026-000090", TODAY.minusDays(12), "40.00"),
                earlierLine("EXP-2026-000091", TODAY.minusDays(5), "40.00"));
        when(lines.findSplitCandidates(anyString(), anyString(), any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
            LocalDate from = call.getArgument(6);
            LocalDate to = call.getArgument(7);
            return history.stream().filter(l -> !l.getTransactionDate().isBefore(from) && !l.getTransactionDate().isAfter(to)).toList();
        });
        // day -12 is 7 days before day -5 (window 3): it is not part of the chain, so only 80 USD are related
        ExpenseEvaluationResult r = evaluate(claim("40.00", "RCP-1", TODAY.minusDays(2), "CC-0001"));
        assertThat(r.aggregate().decision()).isEqualTo(Decision.PENDING_APPROVAL);
        assertThat(r.splitFinding()).isEmpty();
    }
}
