package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.approval.ApprovalRoutingService;
import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.approval.RoutePlan;
import me.niteshh.nexoraflow.approval.RoutingContext;
import me.niteshh.nexoraflow.audit.AnomalyService;
import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.audit.DecisionTraceService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.ReviewAction;
import me.niteshh.nexoraflow.common.ReviewCommand;
import me.niteshh.nexoraflow.common.WorkflowResult;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.common.rules.DecisionAggregator;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;
import me.niteshh.nexoraflow.common.rules.RuleSeverity;
import me.niteshh.nexoraflow.common.rules.RuleVerdict;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.intake.IntakeStatus;
import me.niteshh.nexoraflow.intake.SubmissionRecord;
import me.niteshh.nexoraflow.intake.SubmissionRecordRepository;
import me.niteshh.nexoraflow.policy.EmployeePolicyAssignmentRepository;
import me.niteshh.nexoraflow.policy.HolidayRepository;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeavePolicyRepository;

/**
 * Orchestrates the leave lifecycle: intake -> evaluation -> approval routing -> (approval handled by the
 * approval engine) -> balance consumption. All decisions come from {@link LeaveEvaluationEngine}.
 */
@Service
public class LeaveWorkflowService {

    private final LeaveRequestRepository requests;
    private final EmployeeRepository employees;
    private final LeavePolicyRepository policies;
    private final EmployeePolicyAssignmentRepository assignments;
    private final HolidayRepository holidays;
    private final LeaveBalanceService balances;
    private final LeaveEvaluationEngine engine;
    private final ApprovalRoutingService routing;
    private final ApprovalService approvals;
    private final LeaveStatusUpdater status;
    private final SubmissionRecordRepository submissions;
    private final DecisionTraceService traces;
    private final AnomalyService anomalies;
    private final AuditService audit;
    private final IdService ids;
    private final AppClock clock;
    private final JsonSupport json;

    public LeaveWorkflowService(LeaveRequestRepository requests, EmployeeRepository employees,
                                LeavePolicyRepository policies, EmployeePolicyAssignmentRepository assignments,
                                HolidayRepository holidays, LeaveBalanceService balances, LeaveEvaluationEngine engine,
                                ApprovalRoutingService routing, ApprovalService approvals, LeaveStatusUpdater status,
                                SubmissionRecordRepository submissions, DecisionTraceService traces,
                                AnomalyService anomalies, AuditService audit, IdService ids, AppClock clock,
                                JsonSupport json) {
        this.requests = requests;
        this.employees = employees;
        this.policies = policies;
        this.assignments = assignments;
        this.holidays = holidays;
        this.balances = balances;
        this.engine = engine;
        this.routing = routing;
        this.approvals = approvals;
        this.status = status;
        this.submissions = submissions;
        this.traces = traces;
        this.anomalies = anomalies;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    public LeaveRequest require(String id) {
        return requests.findById(id).orElseThrow(() -> NexoraException.notFound("Leave request", id));
    }

    // ------------------------------------------------------------------ intake

    @Transactional
    public LeaveSubmissionResult submit(LeaveSubmission cmd) {
        Instant now = clock.now();
        LocalDate today = clock.today();

        SubmissionRecord staging = new SubmissionRecord();
        staging.setSubmissionId(ids.submissionId());
        staging.setRequestType(RequestType.LEAVE);
        staging.setReceivedAt(now);
        staging.setSubmittedByRef(cmd.employeeId());
        staging.setRawPayload(json.toJson(cmd));

        List<String> errors = new ArrayList<>();
        Employee employee = cmd.employeeId() == null ? null : employees.findById(cmd.employeeId()).orElse(null);
        if (employee == null) {
            errors.add(ReasonCode.EMPLOYEE_NOT_FOUND + ": " + cmd.employeeId());
        }
        LeavePolicy policy = cmd.leavePolicyId() == null ? null : policies.findById(cmd.leavePolicyId()).orElse(null);
        if (policy == null) {
            errors.add(ReasonCode.INVALID_REFERENCE + ": leavePolicyId " + cmd.leavePolicyId());
        }
        if (cmd.startDate() == null || cmd.endDate() == null) {
            errors.add(ReasonCode.INVALID_DATE_RANGE + ": start and end date are required");
        }
        if (!errors.isEmpty()) {
            staging.setIntakeStatus(IntakeStatus.REJECTED_AT_INTAKE);
            staging.setValidationErrors(json.toJson(errors));
            submissions.save(staging);
            audit.record(AuditCategory.VIOLATION, EntityType.SUBMISSION, staging.getSubmissionId(),
                    AuditEventType.SUBMISSION_REJECTED_AT_INTAKE, null, IntakeStatus.REJECTED_AT_INTAKE.name(),
                    AuditService.SYSTEM_ACTOR, String.join("; ", errors), Map.of("requestType", "LEAVE"));
            return new LeaveSubmissionResult(staging.getSubmissionId(), IntakeStatus.REJECTED_AT_INTAKE, null, errors);
        }

        LeaveRequest request = new LeaveRequest();
        request.setLeaveRequestId(ids.leaveRequestId(today));
        request.setEmployeeId(employee.getEmployeeId());
        request.setLeavePolicyId(policy.getLeavePolicyId());
        request.setStartDate(cmd.startDate());
        request.setEndDate(cmd.endDate());
        long span = Math.max(0, ChronoUnit.DAYS.between(cmd.startDate(), cmd.endDate()) + 1);
        request.setRequestedDays(cmd.requestedDays() != null ? cmd.requestedDays() : BigDecimal.valueOf(span));
        request.setReason(cmd.reason());
        request.setSubmissionDate(today);
        request.setSubmittedAt(now);
        request.setSupportingDocumentRef(cmd.supportingDocumentRef());
        boolean invertedRange = cmd.endDate().isBefore(cmd.startDate());
        // The database only allows an inverted range on a REJECTED record, so such a submission is
        // preserved as a rejected request (never silently discarded, never a live request).
        request.setRequestStatus(invertedRange ? LeaveRequestStatus.REJECTED : LeaveRequestStatus.SUBMITTED);
        requests.save(request);

        staging.setIntakeStatus(invertedRange ? IntakeStatus.ACCEPTED_WITH_ERRORS : IntakeStatus.ACCEPTED);
        if (invertedRange) {
            staging.setValidationErrors(json.toJson(List.of(ReasonCode.INVALID_DATE_RANGE + ": end date before start date")));
        }
        staging.setLeaveRequestId(request.getLeaveRequestId());
        submissions.save(staging);

        audit.record(AuditCategory.WORKFLOW, EntityType.LEAVE_REQUEST, request.getLeaveRequestId(),
                AuditEventType.REQUEST_SUBMITTED, null, LeaveRequestStatus.SUBMITTED.name(), employee.getEmployeeId(),
                "Leave request submitted", Map.of("submissionId", staging.getSubmissionId(),
                        "leavePolicyId", policy.getLeavePolicyId()));

        if (invertedRange) {
            Evaluation ev = compute(request, employee, policy);
            persistEvaluation(request, ev, AuditService.SYSTEM_ACTOR, LeaveRequestStatus.REJECTED);
            request.setDecision(Decision.REJECTED);
            request.setDecisionReason(ev.aggregate.reasonCodesAsText());
            requests.save(request);
            audit.record(AuditCategory.WORKFLOW, EntityType.LEAVE_REQUEST, request.getLeaveRequestId(),
                    AuditEventType.STATUS_CHANGED, LeaveRequestStatus.SUBMITTED.name(), LeaveRequestStatus.REJECTED.name(),
                    AuditService.SYSTEM_ACTOR, "Rejected at intake: invalid date range", Map.of());
        }
        return new LeaveSubmissionResult(staging.getSubmissionId(), staging.getIntakeStatus(), request, errors);
    }

    // ------------------------------------------------------------------ evaluation

    @Transactional
    public WorkflowResult<LeaveRequest> evaluate(String requestId, String actor) {
        LeaveRequest request = require(requestId);
        if (request.getRequestStatus() != LeaveRequestStatus.SUBMITTED) {
            return invalidTransition(request, "EVALUATE", actor);
        }
        Employee employee = employees.findById(request.getEmployeeId()).orElseThrow();
        LeavePolicy policy = policies.findById(request.getLeavePolicyId()).orElseThrow();
        Evaluation ev = compute(request, employee, policy);
        LeaveRequestStatus target = statusFor(ev.aggregate.decision());
        persistEvaluation(request, ev, actor, target);
        status.transition(request, target, ev.aggregate.decision(), ev.aggregate.reasonCodesAsText(), actor,
                "Evaluation: " + ev.aggregate.decision(), Map.of("reasonCodes", ev.aggregate.reasonCodes().toString()));
        applyEvaluationEffects(request, ev, actor);
        return WorkflowResult.accepted(request);
    }

    private void applyEvaluationEffects(LeaveRequest request, Evaluation ev, String actor) {
        if (ev.aggregate.decision() == Decision.PENDING_APPROVAL) {
            balances.reserve(request, actor);
            routing.createRoute(RequestType.LEAVE, request.getLeaveRequestId(), ev.plan, actor);
        } else if (ev.aggregate.reasonCodes().contains(ReasonCode.OVERLAPPING_APPROVED_LEAVE)) {
            List<String> overlapIds = ev.input.approvedOverlaps().stream().map(LeaveRequest::getLeaveRequestId).toList();
            anomalies.record("LEAVE", "OVERLAPPING_APPROVED_LEAVE", EntityType.LEAVE_REQUEST, request.getLeaveRequestId(),
                    overlapIds, "HIGH", ReasonCode.OVERLAPPING_APPROVED_LEAVE, Map.of("detectedAt", "EVALUATION"));
        }
    }

    // ------------------------------------------------------------------ cancellation

    @Transactional
    public WorkflowResult<LeaveRequest> cancel(String requestId, String actor, String reason) {
        LeaveRequest request = require(requestId);
        if (!request.getEmployeeId().equals(actor)) {
            return violation(request, "CANCEL", actor, ReasonCode.UNAUTHORIZED_APPROVER,
                    "Only the requester can cancel a leave request");
        }
        LeaveRequestStatus from = request.getRequestStatus();
        if (!LeaveRequestStatus.LIFECYCLE.canTransition(from, LeaveRequestStatus.CANCELLED)) {
            return invalidTransition(request, "CANCEL", actor);
        }
        if (from == LeaveRequestStatus.APPROVED && request.getStartDate().isBefore(clock.today())) {
            return violation(request, "CANCEL", actor, ReasonCode.INVALID_STATE_TRANSITION,
                    "Leave that has already started cannot be cancelled");
        }
        if (from == LeaveRequestStatus.PENDING_APPROVAL) {
            balances.release(request, actor, "Cancelled by requester while pending");
        } else if (from == LeaveRequestStatus.APPROVED) {
            balances.restore(request, actor, "Approved leave cancelled by requester");
        }
        approvals.cancelOpenSteps(RequestType.LEAVE, requestId, actor, "Leave request cancelled by requester");
        request.setCancelledAt(clock.now());
        status.transition(request, LeaveRequestStatus.CANCELLED, Decision.CANCELLED,
                ReasonCode.CANCELLED_BY_REQUESTER.name(), actor, reason == null ? "Cancelled by requester" : reason, Map.of());
        return WorkflowResult.accepted(request);
    }

    // ------------------------------------------------------------------ analyst review

    @Transactional
    public WorkflowResult<LeaveRequest> review(String requestId, ReviewCommand cmd) {
        LeaveRequest request = require(requestId);
        String actor = cmd.actorEmployeeId();
        LeaveRequestStatus from = request.getRequestStatus();
        if (from != LeaveRequestStatus.NEEDS_REVIEW && from != LeaveRequestStatus.FLAGGED) {
            return invalidTransition(request, "REVIEW", actor);
        }
        if (cmd.action() == ReviewAction.REJECT) {
            status.transition(request, LeaveRequestStatus.REJECTED, Decision.REJECTED,
                    ReasonCode.REJECTED_BY_ANALYST.name(), actor, cmd.comment(), Map.of());
            audit.record(AuditCategory.WORKFLOW, EntityType.LEAVE_REQUEST, requestId, AuditEventType.REVIEW_RESOLVED,
                    from.name(), LeaveRequestStatus.REJECTED.name(), actor, ReasonCode.REJECTED_BY_ANALYST.name(),
                    Map.of("comment", String.valueOf(cmd.comment())));
            return WorkflowResult.accepted(request);
        }
        Employee employee = employees.findById(request.getEmployeeId()).orElseThrow();
        LeavePolicy policy = policies.findById(request.getLeavePolicyId()).orElseThrow();
        Evaluation ev = compute(request, employee, policy);
        if (ev.aggregate.decision() == Decision.REJECTED) {
            return violation(request, "REVIEW_RELEASE", actor, ReasonCode.INVALID_STATE_TRANSITION,
                    "Cannot release: hard rule failures remain " + ev.aggregate.reasonCodes());
        }
        if (!ev.plan.resolvable()) {
            return violation(request, "REVIEW_RELEASE", actor, ReasonCode.NO_VALID_APPROVER, ev.plan.failureMessage());
        }
        persistEvaluation(request, ev, actor, LeaveRequestStatus.PENDING_APPROVAL);
        status.transition(request, LeaveRequestStatus.PENDING_APPROVAL, Decision.PENDING_APPROVAL,
                ReasonCode.RELEASED_BY_ANALYST.name(), actor, cmd.comment(),
                Map.of("waivedReasonCodes", ev.aggregate.reasonCodes().toString()));
        audit.record(AuditCategory.WORKFLOW, EntityType.LEAVE_REQUEST, requestId, AuditEventType.REVIEW_RESOLVED,
                from.name(), LeaveRequestStatus.PENDING_APPROVAL.name(), actor, ReasonCode.RELEASED_BY_ANALYST.name(),
                Map.of("comment", String.valueOf(cmd.comment()), "waivedReasonCodes", ev.aggregate.reasonCodes().toString()));
        balances.reserve(request, actor);
        routing.createRoute(RequestType.LEAVE, requestId, ev.plan, actor);
        return WorkflowResult.accepted(request);
    }

    // ------------------------------------------------------------------ evaluation internals

    private record Evaluation(LeaveEvaluationInput input, LeaveEvaluationResult result, List<RuleOutcome> outcomes,
                              DecisionAggregator.Aggregate aggregate, RoutePlan plan) {
    }

    private Evaluation compute(LeaveRequest request, Employee employee, LeavePolicy policy) {
        Set<LocalDate> holidayDates = new HashSet<>();
        List<LeaveRequest> overlaps = List.of();
        BigDecimal available = null;
        boolean validRange = !request.getEndDate().isBefore(request.getStartDate());
        if (validRange) {
            holidayDates.addAll(holidays.findHolidayDates(employee.getCountry(), request.getStartDate(), request.getEndDate()));
            overlaps = requests.findOverlapping(employee.getEmployeeId(), request.getLeaveRequestId(),
                    List.of(LeaveRequestStatus.APPROVED), request.getStartDate(), request.getEndDate());
            available = balances.find(employee.getEmployeeId(), policy.getLeavePolicyId(), request.getStartDate().getYear())
                    .map(LeaveBalance::computeAvailable).orElse(null);
        }
        LeaveEvaluationInput input = new LeaveEvaluationInput(request.getLeaveRequestId(), employee, policy,
                assignments.findByEmployeeIdAndLeavePolicyId(employee.getEmployeeId(), policy.getLeavePolicyId()),
                request.getStartDate(), request.getEndDate(), request.getSubmissionDate(),
                request.getSupportingDocumentRef(), holidayDates, available, overlaps);
        LeaveEvaluationResult result = engine.evaluate(input);
        List<RuleOutcome> outcomes = new ArrayList<>(result.outcomes());
        DecisionAggregator.Aggregate aggregate = DecisionAggregator.aggregate(outcomes);
        RoutePlan plan = null;
        if (aggregate.decision() == Decision.PENDING_APPROVAL) {
            plan = routing.plan(new RoutingContext(RequestType.LEAVE, employee, result.businessDays(), Set.of(), clock.today()));
            outcomes.add(plan.resolvable()
                    ? RuleOutcome.pass("LV-11", "APPROVER_RESOLVABLE", "Approval route: " + plan.describe())
                    : RuleOutcome.fail("LV-11", "APPROVER_RESOLVABLE", RuleSeverity.REVIEW, ReasonCode.NO_VALID_APPROVER,
                            plan.failureMessage()));
            aggregate = DecisionAggregator.aggregate(outcomes);
        } else if (aggregate.decision() == Decision.FLAGGED || aggregate.decision() == Decision.NEEDS_REVIEW) {
            // report the route the request WOULD follow once released, for analyst context
            plan = routing.plan(new RoutingContext(RequestType.LEAVE, employee, result.businessDays(), Set.of(), clock.today()));
        }
        return new Evaluation(input, result, outcomes, aggregate, plan == null ? RoutePlan.notApplicable() : plan);
    }

    private void persistEvaluation(LeaveRequest request, Evaluation ev, String actor, LeaveRequestStatus target) {
        request.setCalculatedBusinessDays(ev.result.businessDays());
        requests.save(request);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("requestId", request.getLeaveRequestId());
        trace.put("requestType", "LEAVE");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("employeeId", request.getEmployeeId());
        input.put("leavePolicyId", request.getLeavePolicyId());
        input.put("startDate", String.valueOf(request.getStartDate()));
        input.put("endDate", String.valueOf(request.getEndDate()));
        input.put("requestedDays", request.getRequestedDays());
        input.put("submissionDate", String.valueOf(request.getSubmissionDate()));
        input.put("supportingDocumentRef", request.getSupportingDocumentRef());
        trace.put("input", input);
        trace.put("applicablePolicy", ev.input.policy().getLeavePolicyId());
        trace.put("rulesEvaluated", ev.outcomes);
        trace.put("rulesPassed", ev.outcomes.stream().filter(o -> o.verdict() == RuleVerdict.PASS).map(RuleOutcome::ruleId).toList());
        trace.put("rulesFailed", ev.outcomes.stream().filter(RuleOutcome::failed).map(RuleOutcome::ruleId).toList());
        trace.put("rulesSkipped", ev.outcomes.stream().filter(o -> o.verdict() == RuleVerdict.SKIPPED).map(RuleOutcome::ruleId).toList());
        trace.put("decision", ev.aggregate.decision().name());
        trace.put("reasonCodes", ev.aggregate.reasonCodes().stream().map(Enum::name).toList());
        trace.put("businessDays", ev.result.businessDays());
        trace.put("approvalPath", ev.plan.resolvable() ? ev.plan.steps() : List.of());
        trace.put("approvalPathText", ev.plan.describe());
        traces.record(RequestType.LEAVE, request.getLeaveRequestId(), ev.aggregate.decision().name(),
                ev.aggregate.reasonCodesAsText(), ev.input.policy().getLeavePolicyId(), trace);

        audit.record(AuditCategory.DECISION, EntityType.LEAVE_REQUEST, request.getLeaveRequestId(),
                AuditEventType.REQUEST_EVALUATED, request.getRequestStatus().name(), target.name(), actor,
                ev.aggregate.reasonCodesAsText().isEmpty() ? "All rules passed" : ev.aggregate.reasonCodesAsText(),
                Map.of("decision", ev.aggregate.decision().name(), "businessDays", String.valueOf(ev.result.businessDays()),
                        "approvalPath", ev.plan.describe()));
    }

    private static LeaveRequestStatus statusFor(Decision decision) {
        return switch (decision) {
            case PENDING_APPROVAL -> LeaveRequestStatus.PENDING_APPROVAL;
            case REJECTED -> LeaveRequestStatus.REJECTED;
            case FLAGGED -> LeaveRequestStatus.FLAGGED;
            case NEEDS_REVIEW, ON_HOLD -> LeaveRequestStatus.NEEDS_REVIEW;
            case APPROVED, CANCELLED -> throw new IllegalStateException("Evaluation cannot produce " + decision);
        };
    }

    private WorkflowResult<LeaveRequest> invalidTransition(LeaveRequest request, String action, String actor) {
        return violation(request, action, actor, ReasonCode.INVALID_STATE_TRANSITION,
                "Action " + action + " is not allowed while the request is " + request.getRequestStatus());
    }

    private WorkflowResult<LeaveRequest> violation(LeaveRequest request, String action, String actor, ReasonCode code,
                                                   String message) {
        audit.record(AuditCategory.VIOLATION, EntityType.LEAVE_REQUEST, request.getLeaveRequestId(),
                AuditEventType.INVALID_TRANSITION_REJECTED, request.getRequestStatus().name(),
                request.getRequestStatus().name(), actor, code.name(), Map.of("attemptedAction", action, "message", message));
        return WorkflowResult.rejected(code, message);
    }
}
