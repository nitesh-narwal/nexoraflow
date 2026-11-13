package me.niteshh.nexoraflow.approval;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, String>, JpaSpecificationExecutor<ApprovalStep> {

    List<ApprovalStep> findByLeaveRequestIdOrderByStepSequence(String leaveRequestId);

    List<ApprovalStep> findByExpenseClaimIdOrderByStepSequence(String expenseClaimId);

    List<ApprovalStep> findByApproverEmployeeIdAndApprovalStatusOrderByAssignedAtAscApprovalStepIdAsc(
            String approverEmployeeId, ApprovalStatus approvalStatus);
}
