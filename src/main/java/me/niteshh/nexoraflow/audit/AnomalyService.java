package me.niteshh.nexoraflow.audit;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.configuration.JsonSupport;

/** Records anomalies raised by the engines and mirrors each one into the audit trail. */
@Service
public class AnomalyService {

    private final DetectedAnomalyRepository anomalies;
    private final AuditService audit;
    private final IdService ids;
    private final AppClock clock;
    private final JsonSupport json;

    public AnomalyService(DetectedAnomalyRepository anomalies, AuditService audit, IdService ids, AppClock clock,
                          JsonSupport json) {
        this.anomalies = anomalies;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    public DetectedAnomaly record(String domain, String anomalyType, String entityType, String entityId,
                                  List<String> relatedIds, String severity, ReasonCode reasonCode,
                                  Map<String, Object> details) {
        DetectedAnomaly a = new DetectedAnomaly();
        a.setAnomalyId(ids.anomalyId());
        a.setAnomalyDomain(domain);
        a.setAnomalyType(anomalyType);
        a.setEntityType(entityType);
        a.setEntityId(entityId);
        a.setRelatedEntityIds(relatedIds == null || relatedIds.isEmpty() ? null : String.join(",", relatedIds));
        a.setSeverity(severity);
        a.setReasonCode(reasonCode.name());
        a.setDetectedAt(clock.now());
        a.setDetails(details == null || details.isEmpty() ? null : json.toJson(details));
        anomalies.save(a);
        audit.record(AuditCategory.DECISION, entityType, entityId, AuditEventType.ANOMALY_DETECTED, null, null,
                AuditService.SYSTEM_ACTOR, reasonCode.name(),
                Map.of("anomalyId", a.getAnomalyId(), "anomalyType", anomalyType, "severity", severity));
        return a;
    }
}
