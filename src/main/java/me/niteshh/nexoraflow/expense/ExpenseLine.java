package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

@Entity
@Table(name = "expense_line")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseLine extends PersistableEntity {

    @Id
    @Column(name = "expense_line_id")
    private String expenseLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_claim_id")
    private ExpenseClaim claim;

    @Column(name = "line_number")
    private int lineNumber;

    @Column(name = "expense_policy_id")
    private String expensePolicyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "expense_category")
    private ExpenseCategory expenseCategory;

    @Column(name = "merchant_name")
    private String merchantName;

    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    private String description;

    private BigDecimal amount;

    private String currency;

    /** Amount converted to the reporting currency (USD) at evaluation time, for traceability. */
    @Column(name = "amount_reporting")
    private BigDecimal amountReporting;

    @Column(name = "receipt_id")
    private String receiptId;

    @Column(name = "submitted_receipt_ref")
    private String submittedReceiptRef;

    @Column(name = "cost_center_id")
    private String costCenterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_status")
    private LineStatus lineStatus = LineStatus.SUBMITTED;

    @Column(name = "validation_reason")
    private String validationReason;

    @Override
    public String getId() {
        return expenseLineId;
    }
}
