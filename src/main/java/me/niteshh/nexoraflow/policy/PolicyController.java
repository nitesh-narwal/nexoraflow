package me.niteshh.nexoraflow.policy;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Policies and configuration", description = "Read access to the configurable business policies")
public class PolicyController {

    private final LeavePolicyRepository leavePolicies;
    private final ExpensePolicyRepository expensePolicies;

    public PolicyController(LeavePolicyRepository leavePolicies, ExpensePolicyRepository expensePolicies) {
        this.leavePolicies = leavePolicies;
        this.expensePolicies = expensePolicies;
    }

    @GetMapping("/leave-policies")
    @Operation(summary = "Leave policies (filter by country, type, status)")
    public PageResponse<PolicyDtos.LeavePolicyResponse> leavePolicies(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) LeaveType leaveType,
            @RequestParam(required = false) PolicyStatus status,
            @PageableDefault(size = 50, sort = "leavePolicyId") Pageable pageable) {
        var spec = Specs.<LeavePolicy>allOf(Specs.<LeavePolicy>eq("country", country), Specs.<LeavePolicy>eq("leaveType", leaveType),
                Specs.<LeavePolicy>eq("policyStatus", status));
        return PageResponse.of(leavePolicies.findAll(spec, pageable), PolicyDtos.LeavePolicyResponse::from);
    }

    @GetMapping("/expense-policies")
    @Operation(summary = "Expense policies (filter by country, category, status); all versions are returned with their effective dates")
    public PageResponse<PolicyDtos.ExpensePolicyResponse> expensePolicies(
            @RequestParam(required = false) String country,
            @RequestParam(required = false) ExpenseCategory category,
            @RequestParam(required = false) PolicyStatus status,
            @PageableDefault(size = 50, sort = "expensePolicyId") Pageable pageable) {
        var spec = Specs.<ExpensePolicy>allOf(Specs.<ExpensePolicy>eq("country", country),
                Specs.<ExpensePolicy>eq("expenseCategory", category), Specs.<ExpensePolicy>eq("policyStatus", status));
        return PageResponse.of(expensePolicies.findAll(spec, pageable), PolicyDtos.ExpensePolicyResponse::from);
    }
}
