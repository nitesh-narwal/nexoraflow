package me.niteshh.nexoraflow.leave;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.EntityType;

/** The only place that changes a leave request's status: checks the lifecycle and writes the audit event. */
@Component
public class LeaveStatusUpdater {

    private final LeaveRequestRepository requests;
    private final AuditService audit;

    public LeaveStatusUpdater(LeaveRequestRepository requests, AuditService audit) {
        this.requests = requests;
        this.audit = audit;
    }

    public void transition(LeaveRequest request, LeaveRequestStatus to, Decision decision, String reasonCodes,
                           String actor, String auditReason, Map<String, Object> metadata) {
        LeaveRequestStatus from = request.getRequestStatus();
        if (!LeaveRequestStatus.LIFECYCLE.canTransition(from, to)) {
            throw new IllegalStateException("Illegal leave transition " + from + " -> " + to + " for "
                    + request.getLeaveRequestId());
        }
        request.setRequestStatus(to);
        request.setDecision(decision);
        request.setDecisionReason(reasonCodes);
        requests.save(request);
        Map<String, Object> meta = new LinkedHashMap<>();
        if (metadata != null) {
            meta.putAll(metadata);
        }
        audit.record(AuditCategory.WORKFLOW, EntityType.LEAVE_REQUEST, request.getLeaveRequestId(),
                AuditEventType.STATUS_CHANGED, from.name(), to.name(), actor, auditReason, meta);
    }
}
