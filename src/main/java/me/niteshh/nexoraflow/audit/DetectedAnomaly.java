package me.niteshh.nexoraflow.audit;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** An anomaly raised by an engine (split claim, duplicate receipt, leave overlap, ...). */
@Entity
@Table(name = "detected_anomaly")
@Getter
@Setter
@NoArgsConstructor
public class DetectedAnomaly extends PersistableEntity {

    @Id
    @Column(name = "anomaly_id")
    private String anomalyId;

    @Column(name = "anomaly_domain")
    private String anomalyDomain;

    @Column(name = "anomaly_type")
    private String anomalyType;

    @Column(name = "entity_type")
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    @Column(name = "related_entity_ids")
    private String relatedEntityIds;

    private String severity;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "detected_at")
    private Instant detectedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    private String status = "OPEN";

    @Override
    public String getId() {
        return anomalyId;
    }
}
