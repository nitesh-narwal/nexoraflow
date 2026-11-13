package me.niteshh.nexoraflow.approval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.id.IdService;

/**
 * Plans (read-only) and creates (write) approval routes. Planning is separate from creation so that the
 * evaluation of a request can report its approval path in the decision explanation before any step exists.
 */
@Service
public class ApprovalRoutingService {

    private final ApprovalRuleResolver ruleResolver;
    private final ApproverResolver approverResolver;
    private final ApprovalStepRepository steps;
    private final AuditService audit;
    private final AppClock clock;

    public ApprovalRoutingService(ApprovalRuleResolver ruleResolver, ApproverResolver approverResolver,
                                  ApprovalStepRepository steps, AuditService audit, AppClock clock) {
        this.ruleResolver = ruleResolver;
        this.approverResolver = approverResolver;
        this.steps = steps;
        this.audit = audit;
        this.clock = clock;
    }

    /** Determines the approval route for the context without writing anything. */
    public RoutePlan plan(RoutingContext ctx) {
        List<ApprovalRule> rules = ruleResolver.resolve(ctx);
        if (rules.isEmpty()) {
            return RoutePlan.failed(ReasonCode.NO_VALID_APPROVER,
                    "No active approval rule matches " + ctx.requestType() + " amount " + ctx.amount());
        }
        List<PlannedStep> planned = new ArrayList<>();
        Map<String, Integer> approverFirstLevel = new LinkedHashMap<>();
        for (ApprovalRule rule : rules) {
            var resolution = approverResolver.resolve(rule.getApproverRole(), rule.getApproverDesignation(),
                    ctx.requester(), ctx.effectiveDate());
            if (resolution.isEmpty()) {
                return RoutePlan.failed(ReasonCode.NO_VALID_APPROVER, "No active, authorised approver for level "
                        + rule.getApprovalLevel() + " (" + rule.getApproverRole() + ") of " + ctx.requester().getEmployeeId());
            }
            String approverId = resolution.get().approver().getEmployeeId();
            Integer earlier = approverFirstLevel.get(approverId);
            boolean duplicate = earlier != null;
            if (!duplicate) {
                approverFirstLevel.put(approverId, rule.getApprovalLevel());
            }
            String note = duplicate ? "Same approver as level " + earlier + "; no second approval required"
                    : resolution.get().note();
            planned.add(new PlannedStep(rule.getApprovalLevel(), rule.getApproverRole(), approverId,
                    rule.getApprovalRuleId(), duplicate, resolution.get().escalated(),
                    resolution.get().escalatedFromEmployeeId(), note));
        }
        return RoutePlan.of(planned);
    }

    /** Creates the approval steps of a planned route; the first non-skipped step becomes PENDING. */
    @Transactional
    public List<ApprovalStep> createRoute(RequestType type, String requestId, RoutePlan plan, String actor) {
        if (!plan.resolvable()) {
            throw new IllegalArgumentException("Cannot create steps for an unresolvable route");
        }
        Instant now = clock.now();
        List<ApprovalStep> created = new ArrayList<>();
        int sequence = existingMaxSequence(type, requestId);
        boolean activated = false;
        for (PlannedStep planned : plan.steps()) {
            sequence++;
            ApprovalStep step = new ApprovalStep();
            step.setApprovalStepId(IdService.approvalStepId(requestId, sequence));
            step.setRequestType(type);
            if (type == RequestType.LEAVE) {
                step.setLeaveRequestId(requestId);
            } else {
                step.setExpenseClaimId(requestId);
            }
            step.setStepSequence(sequence);
            step.setApprovalLevel(planned.level());
            step.setApproverRole(planned.role());
            step.setApproverEmployeeId(planned.approverEmployeeId());
            step.setApprovalRuleId(planned.approvalRuleId());
            if (planned.skipped()) {
                step.setApprovalStatus(ApprovalStatus.SKIPPED);
                step.setDecisionReason(planned.note());
            } else if (!activated) {
                step.setApprovalStatus(ApprovalStatus.PENDING);
                step.setAssignedAt(now);
                activated = true;
            } else {
                step.setApprovalStatus(ApprovalStatus.WAITING);
            }
            steps.save(step);
            created.add(step);
            if (planned.escalated()) {
                audit.record(AuditCategory.WORKFLOW, EntityType.forRequest(type), requestId,
                        AuditEventType.APPROVER_ESCALATED_UNAVAILABLE, null, null, actor, planned.note(),
                        Map.of("stepId", step.getApprovalStepId(), "escalatedFrom", String.valueOf(planned.escalatedFromEmployeeId()),
                                "assignedTo", planned.approverEmployeeId()));
            }
        }
        audit.record(AuditCategory.WORKFLOW, EntityType.forRequest(type), requestId, AuditEventType.APPROVAL_ROUTE_CREATED,
                null, null, actor, plan.describe(), Map.of("steps", created.stream().map(ApprovalStep::getApprovalStepId).toList()));
        created.stream().filter(s -> s.getApprovalStatus() == ApprovalStatus.PENDING).findFirst().ifPresent(s ->
                audit.record(AuditCategory.WORKFLOW, EntityType.APPROVAL_STEP, s.getApprovalStepId(),
                        AuditEventType.APPROVAL_STEP_ACTIVATED, ApprovalStatus.WAITING.name(), ApprovalStatus.PENDING.name(),
                        actor, "First approval step assigned to " + s.getApproverEmployeeId(),
                        Map.of("requestId", requestId, "approverRole", s.getApproverRole().name())));
        return created;
    }

    private int existingMaxSequence(RequestType type, String requestId) {
        List<ApprovalStep> existing = type == RequestType.LEAVE
                ? steps.findByLeaveRequestIdOrderByStepSequence(requestId)
                : steps.findByExpenseClaimIdOrderByStepSequence(requestId);
        return existing.stream().mapToInt(ApprovalStep::getStepSequence).max().orElse(0);
    }
}
