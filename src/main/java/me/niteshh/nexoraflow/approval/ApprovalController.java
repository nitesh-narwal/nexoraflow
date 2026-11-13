package me.niteshh.nexoraflow.approval;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Approval steps: pending work, approve, reject, escalate")
public class ApprovalController {

    private final ApprovalService approvals;
    private final ApprovalStepRepository steps;

    public ApprovalController(ApprovalService approvals, ApprovalStepRepository steps) {
        this.approvals = approvals;
        this.steps = steps;
    }

    @GetMapping("/pending")
    @Operation(summary = "Approval steps awaiting action (filter by approver and request type)")
    public PageResponse<ApprovalDtos.ApprovalStepResponse> pending(
            @RequestParam(required = false) String approverId,
            @RequestParam(required = false) RequestType requestType,
            @PageableDefault(size = 20, sort = "assignedAt") Pageable pageable) {
        var spec = Specs.<ApprovalStep>allOf(Specs.<ApprovalStep>eq("approvalStatus", ApprovalStatus.PENDING),
                Specs.<ApprovalStep>eq("approverEmployeeId", approverId), Specs.<ApprovalStep>eq("requestType", requestType));
        return PageResponse.of(steps.findAll(spec, pageable), ApprovalDtos.ApprovalStepResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One approval step")
    public ApprovalDtos.ApprovalStepResponse get(@PathVariable String id) {
        return ApprovalDtos.ApprovalStepResponse.from(approvals.require(id));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve a step",
            description = "Requesters cannot approve their own requests; only the active, assigned approver may act, in order.")
    public ApprovalDtos.ApprovalStepResponse approve(@PathVariable String id, @Valid @RequestBody ApprovalDtos.ActionRequest body) {
        return ApprovalDtos.ApprovalStepResponse.from(approvals.approve(id, body.toCommand()).orThrow());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a step (the request is rejected and no further steps run)")
    public ApprovalDtos.ApprovalStepResponse reject(@PathVariable String id, @Valid @RequestBody ApprovalDtos.ActionRequest body) {
        return ApprovalDtos.ApprovalStepResponse.from(approvals.reject(id, body.toCommand()).orThrow());
    }

    @PostMapping("/{id}/escalate")
    @Operation(summary = "Escalate a step to the approver's manager (returns the replacement step)")
    public ApprovalDtos.ApprovalStepResponse escalate(@PathVariable String id, @Valid @RequestBody ApprovalDtos.ActionRequest body) {
        return ApprovalDtos.ApprovalStepResponse.from(approvals.escalate(id, body.toCommand()).orThrow());
    }
}
