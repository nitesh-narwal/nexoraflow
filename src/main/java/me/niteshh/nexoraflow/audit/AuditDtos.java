package me.niteshh.nexoraflow.audit;

import java.time.Instant;
import java.util.Map;

import me.niteshh.nexoraflow.configuration.JsonSupport;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditEventResponse(String eventId, AuditCategory category, String entityType, String entityId,
                                     String eventType, String previousState, String newState, String actor,
                                     Instant occurredAt, String reason, Map<String, Object> metadata) {
        public static AuditEventResponse from(AuditEvent e, JsonSupport json) {
            return new AuditEventResponse(e.getEventId(), e.getEventCategory(), e.getEntityType(), e.getEntityId(),
                    e.getEventType(), e.getPreviousState(), e.getNewState(), e.getActor(), e.getOccurredAt(), e.getReason(),
                    e.getMetadata() == null ? null : json.toMap(e.getMetadata()));
        }
    }
}
