package me.niteshh.nexoraflow.generator.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import me.niteshh.nexoraflow.employee.AssignmentType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeCostCenter;
import me.niteshh.nexoraflow.expense.ExpenseSubmission;
import me.niteshh.nexoraflow.generator.catalog.BusinessPolicyBaseline;
import me.niteshh.nexoraflow.generator.catalog.ExpenseCatalog;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;

/** Shared helpers to build expense submissions: policies, money, receipts, merchants, cost centers and trackers. */
public final class ClaimFactory {

    /** A line before currency conversion decisions are final. */
    public record LineDraft(String category, String merchant, LocalDate date, String description, BigDecimal amountLocal,
                            boolean withReceipt) {
    }

    private final PlanningContext ctx;
    private final Map<String, BigDecimal> daily = new HashMap<>();
    private final Map<String, BigDecimal> monthly = new HashMap<>();
    private final Map<String, BigDecimal> budgetCommitted = new HashMap<>();
    private final Map<String, List<Object[]>> merchantLedger = new HashMap<>();

    public ClaimFactory(PlanningContext ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ policies and money

    /** The policy the documented rules select for a transaction (own implementation, not the engine's resolver). */
    public Optional<ExpensePolicy> policy(String category, String country, me.niteshh.nexoraflow.employee.EmploymentType type, LocalDate date) {
        return ctx.expensePolicies.stream()
                .filter(p -> p.getExpenseCategory().name().equals(category) && p.getCountry().equals(country))
                .filter(p -> p.getPolicyStatus() == me.niteshh.nexoraflow.policy.PolicyStatus.ACTIVE)
                .filter(p -> !date.isBefore(p.getEffectiveFrom()) && (p.getEffectiveTo() == null || !date.isAfter(p.getEffectiveTo())))
                .filter(p -> p.getEmployeeType() == PolicyEmployeeType.ALL || p.getEmployeeType().name().equals(type.name()))
                .max(Comparator.comparing((ExpensePolicy p) -> p.getEmployeeType() != PolicyEmployeeType.ALL)
                        .thenComparing(ExpensePolicy::getEffectiveFrom));
    }

    public String currencyOf(Employee e) {
        return BusinessPolicyBaseline.COUNTRY_CURRENCY.get(e.getCountry());
    }

    public BigDecimal rate(String currency) {
        return ctx.rateToUsd.get(currency);
    }

    /** Local-currency amount worth {@code usd} USD, rounded to 2 decimals. */
    public BigDecimal local(String currency, double usd) {
        return BigDecimal.valueOf(usd).divide(rate(currency), 2, RoundingMode.HALF_UP);
    }

    public BigDecimal toUsd(String currency, BigDecimal local) {
        return local.multiply(rate(currency)).setScale(2, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------ trackers

    /** True when adding the amount keeps every limit satisfied. */
    public boolean fitsLimits(Employee e, ExpensePolicy policy, String category, LocalDate date, BigDecimal local) {
        if (local.compareTo(policy.getMaximumPerTransaction()) > 0) {
            return false;
        }
        BigDecimal d = daily.getOrDefault(e.getEmployeeId() + "|" + category + "|" + date, BigDecimal.ZERO).add(local);
        BigDecimal m = monthly.getOrDefault(e.getEmployeeId() + "|" + category + "|" + YearMonth.from(date), BigDecimal.ZERO).add(local);
        return d.compareTo(policy.getMaximumDailyAmount()) <= 0 && m.compareTo(policy.getMaximumMonthlyAmount()) <= 0;
    }

    /** Amount already committed by the employee in a category for a day (or the month of that day). */
    public BigDecimal committed(Employee e, String category, LocalDate date, boolean day) {
        return day ? daily.getOrDefault(e.getEmployeeId() + "|" + category + "|" + date, BigDecimal.ZERO)
                : monthly.getOrDefault(e.getEmployeeId() + "|" + category + "|" + YearMonth.from(date), BigDecimal.ZERO);
    }

    public void addLimits(Employee e, String category, LocalDate date, BigDecimal local) {
        daily.merge(e.getEmployeeId() + "|" + category + "|" + date, local, BigDecimal::add);
        monthly.merge(e.getEmployeeId() + "|" + category + "|" + YearMonth.from(date), local, BigDecimal::add);
    }

    /** Baseline claims may use at most 70% of a cost-center month's allocation. */
    public boolean fitsBudget(String costCenterId, LocalDate bookingDate, BigDecimal usd) {
        Optional<BigDecimal> allocation = allocationUsd(costCenterId, bookingDate);
        if (allocation.isEmpty()) {
            return false;
        }
        BigDecimal committed = budgetCommitted.getOrDefault(costCenterId + "|" + YearMonth.from(bookingDate), BigDecimal.ZERO);
        return committed.add(usd).compareTo(allocation.get().multiply(new BigDecimal("0.70"))) <= 0;
    }

    public void addBudget(String costCenterId, LocalDate bookingDate, BigDecimal usd) {
        budgetCommitted.merge(costCenterId + "|" + YearMonth.from(bookingDate), usd, BigDecimal::add);
    }

    public Optional<BigDecimal> allocationUsd(String costCenterId, LocalDate date) {
        return ctx.budgets.stream()
                .filter(b -> b.getCostCenterId().equals(costCenterId) && b.covers(date))
                .findFirst().map(b -> toUsd(b.getCurrency(), b.getAllocatedAmount()));
    }

    /** Same-employee / same-merchant / same-category activity within 5 days would risk a split-claim or duplicate match. */
    public boolean merchantConflict(Employee e, String merchant, String category, LocalDate date) {
        String key = e.getEmployeeId() + "|" + category + "|" + merchant.toLowerCase();
        for (Object[] seen : merchantLedger.getOrDefault(key, List.of())) {
            if (Math.abs(((LocalDate) seen[0]).toEpochDay() - date.toEpochDay()) <= 5) {
                return true;
            }
        }
        return false;
    }

    public void addMerchant(Employee e, String merchant, String category, LocalDate date) {
        merchantLedger.computeIfAbsent(e.getEmployeeId() + "|" + category + "|" + merchant.toLowerCase(), k -> new ArrayList<>())
                .add(new Object[] {date});
    }

    // ------------------------------------------------------------------ cost centers

    /** The employee's primary cost center on a date, from the assignment history. */
    public Optional<String> primaryCostCenter(Employee e, LocalDate date) {
        return ctx.assignments.stream()
                .filter(a -> a.getEmployeeId().equals(e.getEmployeeId()) && a.getAssignmentType() == AssignmentType.PRIMARY && a.coversDate(date))
                .map(EmployeeCostCenter::getCostCenterId).findFirst();
    }

    public Optional<String> secondaryProjectCostCenter(Employee e, LocalDate date) {
        return ctx.assignments.stream()
                .filter(a -> a.getEmployeeId().equals(e.getEmployeeId()) && a.getAssignmentType() == AssignmentType.SECONDARY && a.coversDate(date))
                .map(EmployeeCostCenter::getCostCenterId)
                .filter(id -> ctx.costCenters.get(id).getCostCenterCode().startsWith("PRJ-")).findFirst();
    }

    // ------------------------------------------------------------------ text, receipts

    public String merchant(String category, Random rng) {
        List<String> pool = ExpenseCatalog.MERCHANTS.get(category);
        return pool == null ? "Unlisted Merchant" : pool.get(rng.nextInt(pool.size()));
    }

    public String description(String category, Random rng) {
        List<String> pool = ExpenseCatalog.DESCRIPTIONS.get(category);
        return pool == null ? "Uncategorised expense" : pool.get(rng.nextInt(pool.size()));
    }

    public ExpenseSubmission.ReceiptUpload receipt(String uniqueSeed, Random rng) {
        String type = ExpenseCatalog.RECEIPT_TYPES.get(rng.nextInt(ExpenseCatalog.RECEIPT_TYPES.size()));
        long size = 15_000L + rng.nextInt(900_000);
        String source = ExpenseCatalog.RECEIPT_SOURCES.get(rng.nextInt(ExpenseCatalog.RECEIPT_SOURCES.size()));
        return new ExpenseSubmission.ReceiptUpload("receipt-" + sha256(uniqueSeed).substring(0, 10) + "." + type.toLowerCase(), type, size,
                sha256("receipt|" + ctx.seeds.seed() + "|" + uniqueSeed), source);
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public ExpenseSubmission.Line toLine(LineDraft d, String currency, String receiptSeed, Random rng) {
        return new ExpenseSubmission.Line(d.category(), d.merchant(), d.date(), d.description(), d.amountLocal(), currency, null,
                d.withReceipt() ? receipt(receiptSeed, rng) : null);
    }
}
