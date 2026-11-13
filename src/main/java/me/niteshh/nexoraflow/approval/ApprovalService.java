package me.niteshh.nexoraflow.approval;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.WorkflowResult;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.employee.EmployeeRules;

/**
 * Executes approval actions (approve, reject, escalate) and enforces every approval restriction:
 * no self-approval, only active assigned approvers with the required authority, steps in the configured
 * order, no overwriting of completed steps, no continuation after rejection.
 *
 * <p>Violations are returned as rejected {@link WorkflowResult}s after an audit event has been written, so
 * the record of the rejected attempt is committed together with the (unchanged) workflow state.
 */
@Service
public class ApprovalService {

    public record ActionCommand(String actorEmployeeId, String comments, String reason) {
    }

    private record Violation(ReasonCode code, String message) {
    }

    private enum Action { APPROVE, REJECT, ESCALATE }

    private final ApprovalStepRepository steps;
    private final ApprovalRuleRepository rules;
    private final EmployeeRepository employees;
    private final ApproverResolver approverResolver;
    private final AuditService audit;
    private final AppClock clock;
    private final Map<RequestType, ApprovalOutcomeHandler> handlers = new EnumMap<>(RequestType.class);

    public ApprovalService(ApprovalStepRepository steps, ApprovalRuleRepository rules, EmployeeRepository employees,
                           ApproverResolver approverResolver, AuditService audit, AppClock clock,
                           List<ApprovalOutcomeHandler> handlerList) {
        this.steps = steps;
        this.rules = rules;
        this.employees = employees;
        this.approverResolver = approverResolver;
        this.audit = audit;
        this.clock = clock;
        handlerList.forEach(h -> handlers.put(h.requestType(), h));
    }

    public ApprovalStep require(String stepId) {
        return steps.findById(stepId).orElseThrow(() -> NexoraException.notFound("Approval step", stepId));
    }

    public List<ApprovalStep> stepsOf(RequestType type, String requestId) {
        return type == RequestType.LEAVE
                ? steps.findByLeaveRequestIdOrderByStepSequence(requestId)
                : steps.findByExpenseClaimIdOrderByStepSequence(requestId);
    }

    @Transactional
    public WorkflowResult<ApprovalStep> approve(String stepId, ActionCommand cmd) {
        ApprovalStep step = require(stepId);
        Optional<Violation> violation = check(step, cmd, Action.APPROVE);
        if (violation.isPresent()) {
            return recordViolation(step, cmd, Action.APPROVE, violation.get());
        }
        Instant now = clock.now();
        step.setApprovalStatus(ApprovalStatus.APPROVED);
        step.setActionAt(now);
        step.setComments(cmd.comments());
        step.setDecisionReason(cmd.reason());
        steps.saveAndFlush(step);
        audit.record(AuditCategory.WORKFLOW, EntityType.APPROVAL_STEP, step.getApprovalStepId(),
                AuditEventType.APPROVAL_STEP_APPROVED, ApprovalStatus.PENDING.name(), ApprovalStatus.APPROVED.name(),
                cmd.actorEmployeeId(), cmd.reason(), Map.of("requestId", step.targetRequestId(),
                        "level", step.getApprovalLevel()));

        List<ApprovalStep> all = stepsOf(step.getRequestType(), step.targetRequestId());
        Optional<ApprovalStep> next = all.stream().filter(s -> s.getApprovalStatus() == ApprovalStatus.WAITING)
                .min(java.util.Comparator.comparingInt(ApprovalStep::getApprovalLevel)
                        .thenComparingInt(ApprovalStep::getStepSequence));
        if (next.isPresent()) {
            ApprovalStep n = next.get();
            n.setApprovalStatus(ApprovalStatus.PENDING);
            n.setAssignedAt(now);
            steps.saveAndFlush(n);
            audit.record(AuditCategory.WORKFLOW, EntityType.APPROVAL_STEP, n.getApprovalStepId(),
                    AuditEventType.APPROVAL_STEP_ACTIVATED, ApprovalStatus.WAITING.name(), ApprovalStatus.PENDING.name(),
                    AuditService.SYSTEM_ACTOR, "Previous level approved; step assigned to " + n.getApproverEmployeeId(),
                    Map.of("requestId", n.targetRequestId(), "level", n.getApprovalLevel()));
        } else if (all.stream().noneMatch(s -> s.getApprovalStatus() == ApprovalStatus.PENDING)) {
            handlers.get(step.getRequestType()).onFinalApproval(step.targetRequestId(), cmd.actorEmployeeId());
        }
        return WorkflowResult.accepted(step);
    }

    @Transactional
    public WorkflowResult<ApprovalStep> reject(String stepId, ActionCommand cmd) {
        ApprovalStep step = require(stepId);
        Optional<Violation> violation = check(step, cmd, Action.REJECT);
        if (violation.isPresent()) {
            return recordViolation(step, cmd, Action.REJECT, violation.get());
        }
        step.setApprovalStatus(ApprovalStatus.REJECTED);
        step.setActionAt(clock.now());
        step.setComments(cmd.comments());
        step.setDecisionReason(cmd.reason());
        steps.saveAndFlush(step);
        audit.record(AuditCategory.WORKFLOW, EntityType.APPROVAL_STEP, step.getApprovalStepId(),
                AuditEventType.APPROVAL_STEP_REJECTED, ApprovalStatus.PENDING.name(), ApprovalStatus.REJECTED.name(),
                cmd.actorEmployeeId(), cmd.reason(), Map.of("requestId", step.targetRequestId(),
                        "level", step.getApprovalLevel()));
        cancelOpenSteps(step.getRequestType(), step.targetRequestId(), cmd.actorEmployeeId(),
                "Request rejected at step " + step.getApprovalStepId());
        handlers.get(step.getRequestType()).onRejected(step.targetRequestId(), cmd.actorEmployeeId(), cmd.reason());
        return WorkflowResult.accepted(step);
    }

    /** Hands the step to the actor's own manager (the next active approver up the reporting line). */
    @Transactional
    public WorkflowResult<ApprovalStep> escalate(String stepId, ActionCommand cmd) {
        ApprovalStep step = require(stepId);
        Optional<Violation> violation = check(step, cmd, Action.ESCALATE);
        if (violation.isPresent()) {
            return recordViolation(step, cmd, Action.ESCALATE, violation.get());
        }
        Employee actor = employees.findById(cmd.actorEmployeeId()).orElseThrow();
        String requesterId = handlers.get(step.getRequestType()).requesterOf(step.targetRequestId()).orElseThrow();
        Instant now = clock.now();
        Optional<Employee> target = approverResolver.ancestors(actor).stream()
                .filter(a -> EmployeeRules.canActOn(a, clock.today()) && !a.getEmployeeId().equals(requesterId))
                .findFirst();
        if (target.isEmpty()) {
            return recordViolation(step, cmd, Action.ESCALATE, new Violation(ReasonCode.NO_VALID_APPROVER,
                    "No active manager above " + actor.getEmployeeId() + " to escalate to"));
        }
        step.setApprovalStatus(ApprovalStatus.ESCALATED);
        step.setActionAt(now);
        step.setComments(cmd.comments());
        step.setDecisionReason(cmd.reason());
        steps.saveAndFlush(step);

        int nextSequence = stepsOf(step.getRequestType(), step.targetRequestId()).stream()
                .mapToInt(ApprovalStep::getStepSequence).max().orElse(0) + 1;
        ApprovalStep replacement = new ApprovalStep();
        replacement.setApprovalStepId(me.niteshh.nexoraflow.common.id.IdService.approvalStepId(step.targetRequestId(), nextSequence));
        replacement.setRequestType(step.getRequestType());
        replacement.setLeaveRequestId(step.getLeaveRequestId());
        replacement.setExpenseClaimId(step.getExpenseClaimId());
        replacement.setStepSequence(nextSequence);
        replacement.setApprovalLevel(step.getApprovalLevel());
        replacement.setApproverRole(step.getApproverRole());
        replacement.setApproverEmployeeId(target.get().getEmployeeId());
        replacement.setApprovalRuleId(step.getApprovalRuleId());
        replacement.setEscalatedFromStepId(step.getApprovalStepId());
        replacement.setApprovalStatus(ApprovalStatus.PENDING);
        replacement.setAssignedAt(now);
        steps.saveAndFlush(replacement);
        audit.record(AuditCategory.WORKFLOW, EntityType.APPROVAL_STEP, step.getApprovalStepId(),
                AuditEventType.APPROVAL_STEP_ESCALATED, ApprovalStatus.PENDING.name(), ApprovalStatus.ESCALATED.name(),
                cmd.actorEmployeeId(), cmd.reason(), Map.of("requestId", step.targetRequestId(),
                        "replacementStep", replacement.getApprovalStepId(), "escalatedTo", target.get().getEmployeeId()));
        return WorkflowResult.accepted(replacement);
    }

    /** Cancels every WAITING / PENDING step of a request (rejection, cancellation, flagging). */
    @Transactional
    public void cancelOpenSteps(RequestType type, String requestId, String actor, String reason) {
        Instant now = clock.now();
        for (ApprovalStep s : stepsOf(type, requestId)) {
            if (s.getApprovalStatus() == ApprovalStatus.WAITING || s.getApprovalStatus() == ApprovalStatus.PENDING) {
                ApprovalStatus previous = s.getApprovalStatus();
                s.setApprovalStatus(ApprovalStatus.CANCELLED);
                s.setActionAt(s.getAssignedAt() != null ? now : null);
                s.setDecisionReason(reason);
                steps.saveAndFlush(s);
                audit.record(AuditCategory.WORKFLOW, EntityType.APPROVAL_STEP, s.getApprovalStepId(),
                        AuditEventType.APPROVAL_STEP_CANCELLED, previous.name(), ApprovalStatus.CANCELLED.name(),
                        actor, reason, Map.of("requestId", requestId));
            }
        }
    }

    //  restrictions

    private Optional<Violation> check(ApprovalStep step, ApprovalService.ActionCommand cmd, Action action) {
        ApprovalOutcomeHandler handler = handlers.get(step.getRequestType());
        String requestId = step.targetRequestId();
        String requesterId = handler.requesterOf(requestId).orElse(null);
        Employee actor = cmd.actorEmployeeId() == null ? null : employees.findById(cmd.actorEmployeeId()).orElse(null);

        if (actor == null) {
            return Optional.of(new Violation(ReasonCode.UNAUTHORIZED_APPROVER, "Unknown actor " + cmd.actorEmployeeId()));
        }
        if (actor.getEmployeeId().equals(requesterId)) {
            return Optional.of(new Violation(ReasonCode.SELF_APPROVAL_NOT_ALLOWED,
                    "Requester " + requesterId + " cannot " + action.name().toLowerCase() + " their own request"));
        }
        if (step.getApprovalStatus().isTerminal()) {
            return Optional.of(new Violation(ReasonCode.STEP_ALREADY_COMPLETED,
                    "Step " + step.getApprovalStepId() + " is already " + step.getApprovalStatus()));
        }
        if (step.getApprovalStatus() == ApprovalStatus.WAITING || hasEarlierOpenStep(step)) {
            return Optional.of(new Violation(ReasonCode.INVALID_STEP_ORDER,
                    "An earlier approval step must be completed first"));
        }
        if (!handler.awaitingApproval(requestId)) {
            return Optional.of(new Violation(ReasonCode.INVALID_STATE_TRANSITION,
                    "Request " + requestId + " is not awaiting approval"));
        }
        if (!EmployeeRules.canActOn(actor, clock.today())) {
            return Optional.of(new Violation(ReasonCode.APPROVER_INACTIVE,
                    "Employee " + actor.getEmployeeId() + " is not an active employee"));
        }
        if (!actor.getEmployeeId().equals(step.getApproverEmployeeId())) {
            return Optional.of(new Violation(ReasonCode.UNAUTHORIZED_APPROVER,
                    "Step " + step.getApprovalStepId() + " is assigned to " + step.getApproverEmployeeId()));
        }
        if (!hasAuthority(actor, step, requesterId)) {
            return Optional.of(new Violation(ReasonCode.UNAUTHORIZED_APPROVER,
                    "Employee " + actor.getEmployeeId() + " lacks the authority required for " + step.getApproverRole()));
        }
        return Optional.empty();
    }

    private boolean hasEarlierOpenStep(ApprovalStep step) {
        return stepsOf(step.getRequestType(), step.targetRequestId()).stream()
                .filter(s -> s != step && !s.getApprovalStepId().equals(step.getApprovalStepId()))
                .filter(s -> s.getApprovalStatus() == ApprovalStatus.PENDING || s.getApprovalStatus() == ApprovalStatus.WAITING)
                .anyMatch(s -> s.getApprovalLevel() < step.getApprovalLevel()
                        || (s.getApprovalLevel() == step.getApprovalLevel() && s.getStepSequence() < step.getStepSequence()));
    }

    private boolean hasAuthority(Employee actor, ApprovalStep step, String requesterId) {
        return switch (step.getApproverRole()) {
            case DIRECT_MANAGER -> isInReportingLine(actor, requesterId);
            case MANAGER -> actor.getJobLevel() <= ApproverResolver.MANAGER_LEVEL && isInReportingLine(actor, requesterId);
            case DEPARTMENT_HEAD -> actor.getJobLevel() <= ApproverResolver.DEPARTMENT_HEAD_LEVEL && isInReportingLine(actor, requesterId);
            case FINANCE_REVIEWER, FINANCE_HEAD -> {
                String designation = step.getApprovalRuleId() == null ? null
                        : rules.findById(step.getApprovalRuleId()).map(ApprovalRule::getApproverDesignation).orElse(null);
                yield designation == null || designation.equals(actor.getDesignation());
            }
        };
    }

    private boolean isInReportingLine(Employee actor, String requesterId) {
        if (requesterId == null) {
            return false;
        }
        return employees.findById(requesterId).map(r -> approverResolver.isAncestor(r, actor.getEmployeeId())).orElse(false);
    }

    private WorkflowResult<ApprovalStep> recordViolation(ApprovalStep step, ActionCommand cmd, Action action, Violation violation) {
        boolean authorization = violation.code() == ReasonCode.SELF_APPROVAL_NOT_ALLOWED
                || violation.code() == ReasonCode.UNAUTHORIZED_APPROVER
                || violation.code() == ReasonCode.APPROVER_INACTIVE;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("stepId", step.getApprovalStepId());
        meta.put("attemptedAction", action.name());
        meta.put("stepStatus", step.getApprovalStatus().name());
        meta.put("message", violation.message());
        audit.record(AuditCategory.VIOLATION, EntityType.forRequest(step.getRequestType()), step.targetRequestId(),
                authorization ? AuditEventType.APPROVAL_ATTEMPT_REJECTED : AuditEventType.INVALID_TRANSITION_REJECTED,
                step.getApprovalStatus().name(), step.getApprovalStatus().name(), cmd.actorEmployeeId(),
                violation.code().name(), meta);
        return WorkflowResult.rejected(violation.code(), violation.message());
    }
}
