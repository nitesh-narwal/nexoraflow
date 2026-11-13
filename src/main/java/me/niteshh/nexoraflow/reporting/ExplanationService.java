package me.niteshh.nexoraflow.reporting;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.approval.ApprovalDtos;
import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.audit.AuditDtos;
import me.niteshh.nexoraflow.audit.AuditEventRepository;
import me.niteshh.nexoraflow.audit.DecisionTrace;
import me.niteshh.nexoraflow.audit.DecisionTraceRepository;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.expense.ExpenseClaimRepository;
import me.niteshh.nexoraflow.leave.LeaveRequestRepository;

/**
 * Builds the reusable decision-explanation structure for a request: input, applicable policy, every rule
 * evaluated (passed / failed / skipped), final decision, reason codes, approval path and the full event history.
 */
@Service
public class ExplanationService {

    public record DecisionExplanation(RequestType requestType, String requestId, String currentStatus, String decision,
                                      List<String> reasonCodes, int evaluationNumber, int evaluationCount,
                                      Instant evaluatedAt, String businessRulesVersion, Map<String, Object> evaluation,
                                      List<ApprovalDtos.ApprovalStepResponse> approvalPath,
                                      List<AuditDtos.AuditEventResponse> eventHistory) {
    }

    private final DecisionTraceRepository traces;
    private final ApprovalService approvals;
    private final AuditEventRepository events;
    private final LeaveRequestRepository leaveRequests;
    private final ExpenseClaimRepository claims;
    private final JsonSupport json;

    public ExplanationService(DecisionTraceRepository traces, ApprovalService approvals, AuditEventRepository events,
                              LeaveRequestRepository leaveRequests, ExpenseClaimRepository claims, JsonSupport json) {
        this.traces = traces;
        this.approvals = approvals;
        this.events = events;
        this.leaveRequests = leaveRequests;
        this.claims = claims;
        this.json = json;
    }

    public DecisionExplanation explain(RequestType type, String requestId) {
        String status;
        if (type == RequestType.LEAVE) {
            status = leaveRequests.findById(requestId).orElseThrow(() -> NexoraException.notFound("Leave request", requestId))
                    .getRequestStatus().name();
        } else {
            status = claims.findById(requestId).orElseThrow(() -> NexoraException.notFound("Expense claim", requestId))
                    .getClaimStatus().name();
        }
        DecisionTrace latest = traces.findFirstByRequestTypeAndRequestIdOrderByEvaluationNumberDesc(type, requestId).orElse(null);
        Map<String, Object> evaluation = latest == null ? new LinkedHashMap<>() : json.toMap(latest.getTrace());
        List<String> reasonCodes = latest == null || latest.getReasonCodes() == null || latest.getReasonCodes().isBlank()
                ? List.of() : List.of(latest.getReasonCodes().split(","));
        return new DecisionExplanation(type, requestId, status, latest == null ? null : latest.getDecision(), reasonCodes,
                latest == null ? 0 : latest.getEvaluationNumber(), traces.countByRequestTypeAndRequestId(type, requestId),
                latest == null ? null : latest.getEvaluatedAt(), latest == null ? null : latest.getRuleVersion(), evaluation,
                approvals.stepsOf(type, requestId).stream().map(ApprovalDtos.ApprovalStepResponse::from).toList(),
                history(type, requestId));
    }

    public List<AuditDtos.AuditEventResponse> history(RequestType type, String requestId) {
        return events.findByEntityTypeAndEntityIdOrderByOccurredAtAscEventIdAsc(EntityType.forRequest(type), requestId).stream()
                .map(e -> AuditDtos.AuditEventResponse.from(e, json)).toList();
    }
}
