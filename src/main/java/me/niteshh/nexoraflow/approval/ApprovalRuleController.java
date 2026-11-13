package me.niteshh.nexoraflow.approval;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;

@RestController
@RequestMapping("/api/v1/approval-rules")
@Tag(name = "Policies and configuration")
public class ApprovalRuleController {

    private final ApprovalRuleRepository rules;

    public ApprovalRuleController(ApprovalRuleRepository rules) {
        this.rules = rules;
    }

    @GetMapping
    @Operation(summary = "Configured approval rules (thresholds live here, not in code)")
    public PageResponse<ApprovalDtos.ApprovalRuleResponse> list(
            @RequestParam(required = false) RequestType workflowType,
            @RequestParam(required = false) ApprovalRule.Status status,
            @RequestParam(required = false) String country,
            @PageableDefault(size = 50, sort = {"workflowType", "approvalLevel", "approvalRuleId"}) Pageable pageable) {
        var spec = Specs.<ApprovalRule>allOf(Specs.<ApprovalRule>eq("workflowType", workflowType),
                Specs.<ApprovalRule>eq("ruleStatus", status), Specs.<ApprovalRule>eq("country", country));
        return PageResponse.of(rules.findAll(spec, pageable), ApprovalDtos.ApprovalRuleResponse::from);
    }
}
