package me.niteshh.nexoraflow.expense;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ExpenseClaimRepository extends JpaRepository<ExpenseClaim, String>, JpaSpecificationExecutor<ExpenseClaim> {

    /** Locks the claim row so concurrent reimbursement requests for the same claim are serialised. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ExpenseClaim c where c.expenseClaimId = :id")
    Optional<ExpenseClaim> findByIdForUpdate(@Param("id") String id);

    List<ExpenseClaim> findByEmployeeIdOrderByClaimDateAscExpenseClaimIdAsc(String employeeId);
}
