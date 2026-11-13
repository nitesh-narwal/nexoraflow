package me.niteshh.nexoraflow.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Machine-readable evaluation of the decision engines against the independent ground truth.
 * Anomaly-detection metrics and ordinary workflow-decision metrics are deliberately kept apart.
 */
public record EvaluationReport(
        Meta meta,
        Totals totals,
        AnomalyDetection anomalyDetection,
        WorkflowDecisions workflowDecisions,
        ReimbursementDecisions reimbursementDecisions,
        Controls controls,
        List<ScenarioBreakdown> scenarios,
        List<Mismatch> mismatches) {

    public record Meta(String businessRulesVersion, String note, int groundTruthRows) {
    }

    /** Record volumes. "valid" = entities labelled as ordinary valid records; "invalid" = entities of designed anomaly scenarios. */
    public record Totals(Map<String, Long> recordsGenerated, long totalRecords, long validRecords, long invalidRecords,
                         long unlabelledRequests) {
    }

    /** Precision / recall over anomaly-labelled entities (true positives), and false positives among valid ones. */
    public record AnomalyDetection(long anomaliesLabelled, long detected, long undetected, long falsePositives,
                                   long falseNegatives, double precision, double recall, double f1,
                                   long validRecordsEvaluated) {
    }

    /** Correctness of final status / approval path for rows that have an expected value. */
    public record WorkflowDecisions(long evaluated, long correctOutcome, long incorrectOutcome, double outcomeAccuracy,
                                    long pathEvaluated, long correctPath, long incorrectPath, double pathAccuracy,
                                    long correctApprovalDecisions, long incorrectApprovalDecisions, double decisionAccuracy) {
    }

    public record ReimbursementDecisions(long evaluated, long correct, long incorrect, double accuracy,
                                         long claimsWithMoreThanOneReimbursement) {
    }

    /**
     * Evidence that the safeguards fired (counts taken from the audit trail and attempt log) and the integrity counters
     * the brief asks for: {@code foreignKeyValidationFailures} and {@code duplicateBusinessIdentifiers} are the number of
     * findings of the validation checks DI-02 and DI-01 (0 on a healthy database).
     */
    public record Controls(long invalidReimbursementsPrevented, long duplicateReimbursementAttempts,
                           long unauthorizedApprovalAttemptsPrevented, long failedLifecycleTransitions,
                           long invalidReferencesPreserved, long submissionsRejectedAtIntake,
                           long foreignKeyValidationFailures, long duplicateBusinessIdentifiers) {
    }

    public record ScenarioBreakdown(String issueType, boolean anomaly, long total, long outcomeCorrect, long reasonOrEvidenceCorrect,
                                    long pathCorrect, long pathEvaluated) {
    }

    public record Mismatch(String scenarioId, String entityType, String entityId, String issueType, String problem,
                           String expectedOutcome, String actualOutcome, String expectedReason, String actualReasons,
                           String expectedPath, String actualPath, String expectedReimbursement, String actualReimbursement) {
    }
}
