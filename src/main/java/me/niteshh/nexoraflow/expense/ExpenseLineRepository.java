package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import me.niteshh.nexoraflow.policy.ExpenseCategory;

public interface ExpenseLineRepository extends JpaRepository<ExpenseLine, String> {

    /** Sum of an employee's committed lines (other claims) in a category/currency over a date range. */
    @Query("""
            select coalesce(sum(l.amount), 0) from ExpenseLine l join l.claim c
            where c.employeeId = :employeeId
              and c.expenseClaimId <> :excludeClaimId
              and c.claimStatus in :statuses
              and l.expenseCategory = :category
              and l.currency = :currency
              and l.transactionDate between :from and :to
            """)
    BigDecimal sumCommitted(@Param("employeeId") String employeeId,
                            @Param("excludeClaimId") String excludeClaimId,
                            @Param("statuses") Collection<ExpenseClaimStatus> statuses,
                            @Param("category") ExpenseCategory category,
                            @Param("currency") String currency,
                            @Param("from") LocalDate from,
                            @Param("to") LocalDate to);

    /** Lines of the same employee, merchant, date, amount and category in claims that are not dead. */
    @Query("""
            select l from ExpenseLine l join l.claim c
            where c.employeeId = :employeeId
              and c.expenseClaimId <> :excludeClaimId
              and c.claimStatus not in :excludedStatuses
              and lower(l.merchantName) = :merchantLower
              and l.transactionDate = :transactionDate
              and l.amount = :amount
              and l.expenseCategory = :category
            order by l.expenseLineId
            """)
    List<ExpenseLine> findExactDuplicates(@Param("employeeId") String employeeId,
                                          @Param("excludeClaimId") String excludeClaimId,
                                          @Param("excludedStatuses") Collection<ExpenseClaimStatus> excludedStatuses,
                                          @Param("merchantLower") String merchantLower,
                                          @Param("transactionDate") LocalDate transactionDate,
                                          @Param("amount") BigDecimal amount,
                                          @Param("category") ExpenseCategory category);

    /** Candidate lines for split-claim detection: same employee, category, merchant and cost center. */
    @Query("""
            select l from ExpenseLine l join l.claim c
            where c.employeeId = :employeeId
              and c.expenseClaimId <> :excludeClaimId
              and c.claimStatus not in :excludedStatuses
              and c.costCenterId = :costCenterId
              and lower(l.merchantName) = :merchantLower
              and l.expenseCategory = :category
              and l.transactionDate between :from and :to
            order by l.transactionDate, l.expenseLineId
            """)
    List<ExpenseLine> findSplitCandidates(@Param("employeeId") String employeeId,
                                          @Param("excludeClaimId") String excludeClaimId,
                                          @Param("excludedStatuses") Collection<ExpenseClaimStatus> excludedStatuses,
                                          @Param("costCenterId") String costCenterId,
                                          @Param("merchantLower") String merchantLower,
                                          @Param("category") ExpenseCategory category,
                                          @Param("from") LocalDate from,
                                          @Param("to") LocalDate to);

    /** Lines in OTHER claims that use a receipt with the given hash (duplicate-receipt detection). */
    @Query("""
            select l from ExpenseLine l join l.claim c, Receipt r
            where l.receiptId = r.receiptId
              and r.receiptHash = :hash
              and c.expenseClaimId <> :excludeClaimId
              and c.claimStatus not in :excludedStatuses
            order by l.expenseLineId
            """)
    List<ExpenseLine> findLinesUsingReceiptHash(@Param("hash") String hash,
                                                @Param("excludeClaimId") String excludeClaimId,
                                                @Param("excludedStatuses") Collection<ExpenseClaimStatus> excludedStatuses);
}
