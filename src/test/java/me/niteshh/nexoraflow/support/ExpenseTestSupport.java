package me.niteshh.nexoraflow.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import me.niteshh.nexoraflow.expense.ExpenseSubmission;

/** Small builders so that expense tests read like business scenarios. */
public final class ExpenseTestSupport {

    private ExpenseTestSupport() {
    }

    public static String sha256(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ExpenseSubmission.ReceiptUpload receipt(String seed) {
        return new ExpenseSubmission.ReceiptUpload("receipt-" + seed + ".pdf", "PDF", 24_000, sha256(seed), "mobile-upload");
    }

    public static ExpenseSubmission.Line line(String category, String merchant, LocalDate date, String amount,
                                              ExpenseSubmission.ReceiptUpload receipt) {
        return new ExpenseSubmission.Line(category, merchant, date, merchant + " " + category.toLowerCase(), new BigDecimal(amount),
                "USD", null, receipt);
    }

    public static ExpenseSubmission claim(String employee, String costCenter, ExpenseSubmission.Line... lines) {
        return claimIn("USD", employee, costCenter, lines);
    }

    public static ExpenseSubmission claimIn(String currency, String employee, String costCenter, ExpenseSubmission.Line... lines) {
        LocalDate min = null;
        LocalDate max = null;
        for (ExpenseSubmission.Line l : lines) {
            if (l.transactionDate() != null) {
                min = min == null || l.transactionDate().isBefore(min) ? l.transactionDate() : min;
                max = max == null || l.transactionDate().isAfter(max) ? l.transactionDate() : max;
            }
        }
        return new ExpenseSubmission(employee, min, max, costCenter, currency, List.of(lines));
    }
}
