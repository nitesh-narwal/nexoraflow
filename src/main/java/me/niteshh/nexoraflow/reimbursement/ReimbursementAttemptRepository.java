package me.niteshh.nexoraflow.reimbursement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReimbursementAttemptRepository extends JpaRepository<ReimbursementAttempt, String> {

    List<ReimbursementAttempt> findByExpenseClaimIdOrderByAttemptedAtAscAttemptIdAsc(String expenseClaimId);
}
