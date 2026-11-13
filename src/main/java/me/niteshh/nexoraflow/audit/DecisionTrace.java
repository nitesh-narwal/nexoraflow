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
import me.niteshh.nexoraflow.common.RequestType;

/** Full rule-by-rule record of one evaluation of a request (the "why" behind a decision). */
@Entity
@Table(name = "decision_trace")
@Getter
@Setter
@NoArgsConstructor
public class DecisionTrace extends PersistableEntity {

    @Id
    @Column(name = "trace_id")
    private String traceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type")
    private RequestType requestType;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "evaluation_number")
    private int evaluationNumber;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    private String decision;

    @Column(name = "reason_codes")
    private String reasonCodes;

    @Column(name = "policy_ref")
    private String policyRef;

    @Column(name = "rule_version")
    private String ruleVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String trace;

    @Override
    public String getId() {
        return traceId;
    }
}
