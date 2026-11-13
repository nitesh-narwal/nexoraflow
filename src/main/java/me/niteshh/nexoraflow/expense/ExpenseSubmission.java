package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * An expense claim exactly as submitted. All references (employee, cost center, receipt) are plain
 * strings so that invalid values can be preserved in staging and reported instead of being lost.
 */
public record ExpenseSubmission(
        String employeeId,
        LocalDate expensePeriodStart,
        LocalDate expensePeriodEnd,
        String costCenterRef,
        String currency,
        List<Line> lines) {

    /**
     * @param receiptRef an existing receipt ID, or null
     * @param receipt    metadata of a receipt uploaded with the submission, or null
     */
    public record Line(
            String category,
            String merchantName,
            LocalDate transactionDate,
            String description,
            BigDecimal amount,
            String currency,
            String receiptRef,
            ReceiptUpload receipt) {
    }

    public record ReceiptUpload(String fileName, String fileType, long fileSizeBytes, String receiptHash,
                                String sourceReference) {
    }
}
