package me.niteshh.nexoraflow.approval;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import me.niteshh.nexoraflow.common.RequestType;

public interface ApprovalRuleRepository extends JpaRepository<ApprovalRule, String>, JpaSpecificationExecutor<ApprovalRule> {

    List<ApprovalRule> findByWorkflowTypeAndRuleStatus(RequestType workflowType, ApprovalRule.Status ruleStatus);

    List<ApprovalRule> findAllByOrderByApprovalRuleId();
}
