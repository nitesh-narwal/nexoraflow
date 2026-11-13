package me.niteshh.nexoraflow.generator.truth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.generator.plan.Plans.ClaimPlan;
import me.niteshh.nexoraflow.generator.plan.Plans.LeavePlan;
import me.niteshh.nexoraflow.generator.plan.Plans.Plan;
import me.niteshh.nexoraflow.generator.plan.Plans.TruthSpec;

/**
 * Persists the independent answer key into the separate {@code ground_truth} schema. It is written only by the
 * generator and read only by the evaluation module; the business engines never touch it.
 */
@Component
public class GroundTruthStore {

    private final JdbcTemplate jdbc;

    public GroundTruthStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Binds plan keys to the entity IDs the simulation assigned and writes one label per plan. Returns the rows written. */
    public int write(List<? extends Plan> plans) {
        Map<String, String> idByKey = new HashMap<>();
        for (Plan p : plans) {
            idByKey.put(p.key, p.truthOnSubmission ? p.submissionId : p.entityId);
        }
        List<Object[]> rows = new ArrayList<>();
        for (Plan p : plans) {
            String entityId = p.truthOnSubmission ? p.submissionId : p.entityId;
            if (entityId == null) {
                continue;
            }
            String entityType = p.truthOnSubmission ? EntityType.SUBMISSION
                    : p instanceof LeavePlan ? EntityType.LEAVE_REQUEST : EntityType.EXPENSE_CLAIM;
            List<TruthSpec> specs = new ArrayList<>();
            if (p.truth != null) {
                specs.add(p.truth);
            }
            specs.addAll(p.extraTruths);
            for (TruthSpec t : specs) {
                List<String> related = new ArrayList<>();
                for (String key : t.relatedPlanKeys()) {
                    String id = idByKey.get(key);
                    if (id != null) {
                        related.add(id);
                    }
                }
                rows.add(new Object[] {t.scenarioId(), entityType, entityId, t.issueType(), t.anomaly(), t.expectedOutcome(),
                        blankToNull(t.expectedReasonCode()), blankToNull(t.expectedApprovalPath()), t.expectedReimbursementStatus(),
                        t.severity(), t.explanation(), t.evidenceType(), related.isEmpty() ? null : String.join(",", related), t.ruleIds()});
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO ground_truth.label (scenario_id, entity_type, entity_id, issue_type, is_anomaly, expected_outcome,
                    expected_reason_code, expected_approval_path, expected_reimbursement_status, severity, explanation,
                    evidence_type, related_entity_ids, business_rule_ids)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        return rows.size();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
