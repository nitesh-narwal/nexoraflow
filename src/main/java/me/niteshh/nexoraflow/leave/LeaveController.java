package me.niteshh.nexoraflow.leave;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
import me.niteshh.nexoraflow.audit.AuditDtos;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.ReviewCommand;
import me.niteshh.nexoraflow.common.web.IntakeRejectedException;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;
import me.niteshh.nexoraflow.reporting.ExplanationService;

@RestController
@RequestMapping("/api/v1/leave-requests")
@Tag(name = "Leave", description = "Leave requests: submit, evaluate, cancel, review, explain")
public class LeaveController {

    private final LeaveWorkflowService workflow;
    private final LeaveRequestRepository requests;
    private final ExplanationService explanations;

    public LeaveController(LeaveWorkflowService workflow, LeaveRequestRepository requests, ExplanationService explanations) {
        this.workflow = workflow;
        this.requests = requests;
        this.explanations = explanations;
    }

    @PostMapping
    @Operation(summary = "Submit a leave request",
            description = "Creates the request in SUBMITTED state. Use ?evaluate=true to evaluate immediately. "
                    + "A submission with an invalid reference is rejected with 422 and preserved in staging.")
    public ResponseEntity<LeaveDtos.LeaveRequestResponse> submit(@Valid @RequestBody LeaveDtos.SubmitLeaveRequest body,
                                                                 @RequestParam(defaultValue = "false") boolean evaluate) {
        LeaveSubmissionResult result = workflow.submit(body.toSubmission());
        if (!result.accepted()) {
            throw new IntakeRejectedException(result.submissionId(), result.errors());
        }
        String id = result.request().getLeaveRequestId();
        if (evaluate) {
            workflow.evaluate(id, body.employeeId()).orThrow();
        }
        return ResponseEntity.created(URI.create("/api/v1/leave-requests/" + id))
                .body(LeaveDtos.LeaveRequestResponse.from(workflow.require(id)));
    }

    @GetMapping
    @Operation(summary = "List leave requests (filter by employee, status, policy, date window)")
    public PageResponse<LeaveDtos.LeaveRequestResponse> list(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) LeaveRequestStatus status,
            @RequestParam(required = false) String leavePolicyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startTo,
            @PageableDefault(size = 20, sort = "leaveRequestId") Pageable pageable) {
        var spec = Specs.<LeaveRequest>allOf(Specs.<LeaveRequest>eq("employeeId", employeeId),
                Specs.<LeaveRequest>eq("requestStatus", status), Specs.<LeaveRequest>eq("leavePolicyId", leavePolicyId),
                Specs.<LeaveRequest, LocalDate>gte("startDate", startFrom), Specs.<LeaveRequest, LocalDate>lte("startDate", startTo));
        return PageResponse.of(requests.findAll(spec, pageable), LeaveDtos.LeaveRequestResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one leave request (LVE-2026-000001)")
    public LeaveDtos.LeaveRequestResponse get(@PathVariable String id) {
        return LeaveDtos.LeaveRequestResponse.from(workflow.require(id));
    }

    @PostMapping("/{id}/evaluate")
    @Operation(summary = "Evaluate a SUBMITTED request with the deterministic leave rules",
            description = "Outcome is PENDING_APPROVAL (approval steps created, balance reserved), REJECTED, FLAGGED or NEEDS_REVIEW.")
    public LeaveDtos.LeaveRequestResponse evaluate(@PathVariable String id, @Valid @RequestBody LeaveDtos.ActorRequest body) {
        return LeaveDtos.LeaveRequestResponse.from(workflow.evaluate(id, body.actorEmployeeId()).orThrow());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a leave request (requester only); reserved or used days are released / restored")
    public LeaveDtos.LeaveRequestResponse cancel(@PathVariable String id, @Valid @RequestBody LeaveDtos.ActorRequest body) {
        return LeaveDtos.LeaveRequestResponse.from(workflow.cancel(id, body.actorEmployeeId(), body.reason()).orThrow());
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Operations analyst resolves a NEEDS_REVIEW / FLAGGED request (release or reject)")
    public LeaveDtos.LeaveRequestResponse review(@PathVariable String id, @Valid @RequestBody LeaveDtos.ReviewRequest body) {
        return LeaveDtos.LeaveRequestResponse.from(workflow.review(id,
                new ReviewCommand(body.actorEmployeeId(), body.action(), body.comment())).orThrow());
    }

    @GetMapping("/{id}/explanation")
    @Operation(summary = "Why was this decision taken? Rules evaluated, passed, failed, approval path, event history")
    public ExplanationService.DecisionExplanation explanation(@PathVariable String id) {
        return explanations.explain(RequestType.LEAVE, id);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Chronological audit events of the request")
    public List<AuditDtos.AuditEventResponse> events(@PathVariable String id) {
        workflow.require(id);
        return explanations.history(RequestType.LEAVE, id);
    }
}
