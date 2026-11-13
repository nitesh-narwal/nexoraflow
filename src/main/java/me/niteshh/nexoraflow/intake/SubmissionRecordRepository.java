package me.niteshh.nexoraflow.intake;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubmissionRecordRepository extends JpaRepository<SubmissionRecord, String> {

    Optional<SubmissionRecord> findByExpenseClaimId(String expenseClaimId);

    Optional<SubmissionRecord> findByLeaveRequestId(String leaveRequestId);

    List<SubmissionRecord> findByIntakeStatusOrderBySubmissionId(IntakeStatus status);
}
