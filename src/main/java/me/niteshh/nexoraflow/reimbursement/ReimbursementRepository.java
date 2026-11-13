package me.niteshh.nexoraflow.reimbursement;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReimbursementRepository extends JpaRepository<Reimbursement, String>, JpaSpecificationExecutor<Reimbursement> {

    Optional<Reimbursement> findByExpenseClaimId(String expenseClaimId);

    boolean existsByExpenseClaimId(String expenseClaimId);
}
