package me.niteshh.nexoraflow.audit;

import java.util.Map;

import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.configuration.JsonSupport;

/**
 * Writes append-only audit events. Every important action in the system goes through this service so the
 * full sequence of events (who, what, previous and new state, why) is preserved.
 */
@Service
public class AuditService {

    public static final String SYSTEM_ACTOR = "SYSTEM";

    private final AuditEventRepository events;
    private final IdService ids;
    private final AppClock clock;
    private final JsonSupport json;

    public AuditService(AuditEventRepository events, IdService ids, AppClock clock, JsonSupport json) {
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    public AuditEvent record(AuditCategory category, String entityType, String entityId, String eventType,
                             String previousState, String newState, String actor, String reason,
                             Map<String, Object> metadata) {
        AuditEvent event = new AuditEvent();
        event.setEventId(ids.auditEventId());
        event.setEventCategory(category);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setEventType(eventType);
        event.setPreviousState(previousState);
        event.setNewState(newState);
        event.setActor(actor == null ? SYSTEM_ACTOR : actor);
        event.setOccurredAt(clock.now());
        event.setReason(truncate(reason, 500));
        event.setMetadata(metadata == null || metadata.isEmpty() ? null : json.toJson(metadata));
        return events.save(event);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
