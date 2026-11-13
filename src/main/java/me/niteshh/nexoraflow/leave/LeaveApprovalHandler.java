package me.niteshh.nexoraflow.leave;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.approval.ApprovalOutcomeHandler;
import me.niteshh.nexoraflow.audit.AnomalyService;
import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;

/** Applies the consequences of the approval workflow to a leave request and its balance. */
@Component
public class LeaveApprovalHandler implements ApprovalOutcomeHandler {

    private final LeaveRequestRepository requests;
    private final LeaveStatusUpdater status;
    private final LeaveBalanceService balances;
    private final AnomalyService anomalies;
    private final AuditService audit;

    public LeaveApprovalHandler(LeaveRequestRepository requests, LeaveStatusUpdater status, LeaveBalanceService balances,
                                AnomalyService anomalies, AuditService audit) {
        this.requests = requests;
        this.status = status;
        this.balances = balances;
        this.anomalies = anomalies;
        this.audit = audit;
    }

    @Override
    public RequestType requestType() {
        return RequestType.LEAVE;
    }

    @Override
    public Optional<String> requesterOf(String requestId) {
        return requests.findById(requestId).map(LeaveRequest::getEmployeeId);
    }

    @Override
    public boolean awaitingApproval(String requestId) {
        return requests.findById(requestId).map(r -> r.getRequestStatus() == LeaveRequestStatus.PENDING_APPROVAL).orElse(false);
    }

    @Override
    @Transactional
    public FinalApprovalOutcome onFinalApproval(String requestId, String actor) {
        LeaveRequest request = requests.findById(requestId).orElseThrow();
        // Re-check at the moment of final approval: another request may have been approved in the meantime.
        List<LeaveRequest> overlaps = requests.findOverlapping(request.getEmployeeId(), requestId,
                List.of(LeaveRequestStatus.APPROVED), request.getStartDate(), request.getEndDate());
        if (!overlaps.isEmpty()) {
            List<String> ids = overlaps.stream().map(LeaveRequest::getLeaveRequestId).toList();
            balances.release(request, actor, "Final approval blocked: overlaps approved leave " + ids);
            status.transition(request, LeaveRequestStatus.FLAGGED, Decision.FLAGGED,
                    ReasonCode.OVERLAPPING_APPROVED_LEAVE.name(), actor,
                    "Final approval blocked by overlap with approved leave", Map.of("overlappingRequests", ids));
            audit.record(AuditCategory.VIOLATION, EntityType.LEAVE_REQUEST, requestId, AuditEventType.FINAL_APPROVAL_BLOCKED,
                    LeaveRequestStatus.PENDING_APPROVAL.name(), LeaveRequestStatus.FLAGGED.name(), actor,
                    ReasonCode.OVERLAPPING_APPROVED_LEAVE.name(), Map.of("overlappingRequests", ids));
            anomalies.record("LEAVE", "OVERLAPPING_APPROVED_LEAVE", EntityType.LEAVE_REQUEST, requestId, ids, "HIGH",
                    ReasonCode.OVERLAPPING_APPROVED_LEAVE, Map.of("detectedAt", "FINAL_APPROVAL"));
            return FinalApprovalOutcome.blocked(ReasonCode.OVERLAPPING_APPROVED_LEAVE, "Overlaps approved leave " + ids);
        }
        balances.consume(request, actor);
        status.transition(request, LeaveRequestStatus.APPROVED, Decision.APPROVED, null, actor,
                "All required approvals completed", Map.of());
        return FinalApprovalOutcome.ok();
    }

    @Override
    @Transactional
    public void onRejected(String requestId, String actor, String reason) {
        LeaveRequest request = requests.findById(requestId).orElseThrow();
        balances.release(request, actor, "Request rejected by approver");
        status.transition(request, LeaveRequestStatus.REJECTED, Decision.REJECTED,
                ReasonCode.REJECTED_BY_APPROVER.name(), actor, reason, Map.of());
    }
}
