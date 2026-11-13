package me.niteshh.nexoraflow.budget;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.configuration.CurrencyService;

/**
 * Budget position of a cost center, ALWAYS derived from source claims:
 * <pre>remaining = allocated - approved spend - pending commitments</pre>
 * Claims are charged to the budget period containing their claim (booking) date. Pending commitments
 * (claims awaiting approval) count only when the budget has {@code commit_pending = true}.
 */
@Service
public class BudgetService {

    public record BudgetPosition(String budgetId, String costCenterId, LocalDate periodStart, LocalDate periodEnd,
                                 String currency, BigDecimal allocated, BigDecimal approvedSpend,
                                 BigDecimal pendingCommitted, BigDecimal remaining) {
    }

    private final BudgetRepository budgets;
    private final JdbcTemplate jdbc;
    private final CurrencyService currencies;

    public BudgetService(BudgetRepository budgets, JdbcTemplate jdbc, CurrencyService currencies) {
        this.budgets = budgets;
        this.jdbc = jdbc;
        this.currencies = currencies;
    }

    /**
     * @param excludeClaimId a claim to leave out of the totals (the claim being evaluated, so it never
     *                       competes with its own commitment)
     */
    public Optional<BudgetPosition> position(String costCenterId, LocalDate bookingDate, String excludeClaimId) {
        return budgets.findForDate(costCenterId, bookingDate)
                .filter(b -> b.getStatus() == BudgetStatus.ACTIVE)
                .map(b -> {
                    BigDecimal approved = spend(b, "APPROVED", excludeClaimId);
                    BigDecimal pending = b.isCommitPending() ? spend(b, "PENDING_APPROVAL", excludeClaimId) : Money.ZERO;
                    BigDecimal remaining = Money.scale(b.getAllocatedAmount().subtract(approved).subtract(pending));
                    return new BudgetPosition(b.getBudgetId(), costCenterId, b.getPeriodStart(), b.getPeriodEnd(),
                            b.getCurrency(), b.getAllocatedAmount(), approved, pending, remaining);
                });
    }

    private BigDecimal spend(Budget budget, String status, String excludeClaimId) {
        BigDecimal total = Money.ZERO;
        var rows = jdbc.queryForList("""
                SELECT currency, COALESCE(SUM(total_amount), 0) AS total
                FROM expense_claim
                WHERE cost_center_id = ? AND claim_date BETWEEN ? AND ? AND claim_status = ? AND expense_claim_id <> ?
                GROUP BY currency ORDER BY currency
                """, budget.getCostCenterId(), java.sql.Date.valueOf(budget.getPeriodStart()),
                java.sql.Date.valueOf(budget.getPeriodEnd()), status, excludeClaimId == null ? "" : excludeClaimId);
        for (var row : rows) {
            BigDecimal amount = (BigDecimal) row.get("total");
            String currency = (String) row.get("currency");
            total = total.add(currencies.convert(amount, currency, budget.getCurrency()));
        }
        return Money.scale(total);
    }
}
