package me.niteshh.nexoraflow.audit;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** Append-only audit / workflow event. Previous and new state make every transition reconstructible. */
@Entity
@Table(name = "audit_event")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent extends PersistableEntity {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_category")
    private AuditCategory eventCategory;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "previous_state")
    private String previousState;

    @Column(name = "new_state")
    private String newState;

    private String actor;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Override
    public String getId() {
        return eventId;
    }
}
