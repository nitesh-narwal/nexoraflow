package me.niteshh.nexoraflow.evaluation;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.configuration.NexoraProperties;

/**
 * Compares what the decision engines actually did with the independent answer key in {@code ground_truth.label}.
 * The engines never read that schema; only this class (and the generator that wrote it) does - so the accuracy
 * figures below cannot be inflated by leaked labels.
 */
@Service
public class EvaluationService {

    /** Reason codes that are human decisions or informational notes, not engine-detected violations. */
    private static final Set<String> NON_VIOLATION_CODES = Set.of("REJECTED_BY_APPROVER", "CANCELLED_BY_REQUESTER",
            "RELEASED_BY_ANALYST", "REJECTED_BY_ANALYST", "ESCALATED_UNAVAILABLE_APPROVER");
    private static final Set<String> EXCEPTION_STATUSES = Set.of("FLAGGED", "ON_HOLD", "NEEDS_REVIEW");
    private static final int MAX_MISMATCHES = 300;

    private record Label(String scenarioId, String entityType, String entityId, String issueType, boolean anomaly,
                         String outcome, String reason, String path, String reimbursement, String evidence) {
    }

    private record Actual(String status, String reasons) {
    }

    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final NexoraProperties properties;
    private final AppClock clock;
    private final me.niteshh.nexoraflow.validation.ValidationService validation;

    public EvaluationService(JdbcTemplate jdbc, JsonSupport json, NexoraProperties properties, AppClock clock,
                             me.niteshh.nexoraflow.validation.ValidationService validation) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.validation = validation;
    }

    /** Number of findings of one validation check (the evaluation reports DI-01 and DI-02 as integrity counters). */
    private long findingsOf(java.util.List<me.niteshh.nexoraflow.validation.ValidationReport.CheckResult> checks, String checkId) {
        return checks.stream().filter(c -> c.id().equals(checkId)).mapToLong(me.niteshh.nexoraflow.validation.ValidationReport.CheckResult::findingCount).sum();
    }

    public EvaluationReport evaluate() {
        List<Label> labels = jdbc.query("""
                SELECT scenario_id, entity_type, entity_id, issue_type, is_anomaly, expected_outcome, expected_reason_code,
                       expected_approval_path, expected_reimbursement_status, evidence_type
                FROM ground_truth.label ORDER BY entity_type, entity_id, scenario_id
                """, (rs, i) -> new Label(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)));

        Map<String, Actual> leave = new HashMap<>();
        jdbc.query("SELECT leave_request_id, request_status, decision_reason FROM leave_request",
                (RowCallback) rs -> leave.put(rs.getString(1), new Actual(rs.getString(2), rs.getString(3))));
        Map<String, Actual> claims = new HashMap<>();
        jdbc.query("SELECT expense_claim_id, claim_status, decision_reason FROM expense_claim",
                (RowCallback) rs -> claims.put(rs.getString(1), new Actual(rs.getString(2), rs.getString(3))));
        Map<String, Actual> submissions = new HashMap<>();
        jdbc.query("SELECT submission_id, intake_status, validation_errors::text FROM submission_record",
                (RowCallback) rs -> submissions.put(rs.getString(1), new Actual(rs.getString(2), rs.getString(3))));

        Map<String, String> paths = new HashMap<>();
        jdbc.query("""
                SELECT COALESCE(leave_request_id, expense_claim_id), approver_role, approver_employee_id
                FROM approval_step WHERE approval_status NOT IN ('SKIPPED', 'ESCALATED')
                ORDER BY 1, approval_level, step_sequence
                """, (RowCallback) rs -> paths.merge(rs.getString(1), rs.getString(2) + "(" + rs.getString(3) + ")", (a, b) -> a + " > " + b));

        Map<String, String> reimbursements = new HashMap<>();
        Map<String, Integer> reimbursementCounts = new HashMap<>();
        jdbc.query("SELECT expense_claim_id, reimbursement_status FROM reimbursement", (RowCallback) rs -> {
            reimbursements.put(rs.getString(1), rs.getString(2));
            reimbursementCounts.merge(rs.getString(1), 1, Integer::sum);
        });
        Map<String, Set<String>> attempts = new HashMap<>();
        jdbc.query("SELECT expense_claim_id, outcome FROM reimbursement_attempt",
                (RowCallback) rs -> attempts.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2)));
        Map<String, Set<String>> auditSignals = new HashMap<>();
        jdbc.query("""
                SELECT entity_id, event_type, COALESCE(reason, '') FROM audit_event
                WHERE event_type IN ('APPROVAL_ATTEMPT_REJECTED', 'APPROVER_ESCALATED_UNAVAILABLE', 'INVALID_TRANSITION_REJECTED')
                """, (RowCallback) rs -> {
            Set<String> set = auditSignals.computeIfAbsent(rs.getString(1), k -> new HashSet<>());
            set.add(rs.getString(2));
            set.add(rs.getString(2) + ":" + rs.getString(3));
        });
        Map<String, Set<String>> anomalyCodes = new HashMap<>();
        jdbc.query("SELECT entity_id, reason_code FROM detected_anomaly",
                (RowCallback) rs -> anomalyCodes.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2)));
        Map<String, List<String>> ledger = new HashMap<>();
        jdbc.query("SELECT leave_request_id, txn_type FROM leave_balance_transaction WHERE leave_request_id IS NOT NULL ORDER BY occurred_at, txn_id",
                (RowCallback) rs -> ledger.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2)));

        Set<String> labelled = new HashSet<>();
        long anomalies = 0, detected = 0, falsePositives = 0, validEvaluated = 0;
        long outcomeEvaluated = 0, outcomeCorrect = 0, pathEvaluated = 0, pathCorrect = 0, decisionCorrect = 0, decisionTotal = 0;
        long rmbEvaluated = 0, rmbCorrect = 0, validRecords = 0, invalidRecords = 0;
        Map<String, long[]> byIssue = new TreeMap<>();
        Map<String, Boolean> issueIsAnomaly = new HashMap<>();
        List<EvaluationReport.Mismatch> mismatches = new ArrayList<>();

        for (Label l : labels) {
            labelled.add(l.entityId());
            Actual actual = switch (l.entityType()) {
                case "LEAVE_REQUEST" -> leave.get(l.entityId());
                case "EXPENSE_CLAIM" -> claims.get(l.entityId());
                default -> submissions.get(l.entityId());
            };
            String actualStatus = actual == null ? "MISSING" : actual.status();
            String actualReasons = actual == null || actual.reasons() == null ? "" : actual.reasons();
            String actualPath = paths.get(l.entityId());
            String actualReimbursement = reimbursements.getOrDefault(l.entityId(), "NONE");
            boolean claim = l.entityType().equals("EXPENSE_CLAIM");

            boolean outcomeOk = l.outcome().equals(actualStatus);
            boolean pathApplicable = l.path() != null && !l.entityType().equals("SUBMISSION");
            boolean pathOk = !pathApplicable || l.path().equals(actualPath);
            boolean reimbursementOk = !claim || l.reimbursement().equals(actualReimbursement);
            boolean evidenceOk = evidence(l, actualReasons, actual, auditSignals, attempts, anomalyCodes, ledger, reimbursementCounts, pathOk);

            outcomeEvaluated++;
            outcomeCorrect += outcomeOk ? 1 : 0;
            if (pathApplicable) {
                pathEvaluated++;
                pathCorrect += pathOk ? 1 : 0;
            }
            decisionTotal++;
            decisionCorrect += outcomeOk && pathOk ? 1 : 0;
            if (claim) {
                rmbEvaluated++;
                rmbCorrect += reimbursementOk ? 1 : 0;
            }

            boolean detectedHere = false;
            if (l.anomaly()) {
                anomalies++;
                invalidRecords++;
                detectedHere = evidenceOk && outcomeOk;
                detected += detectedHere ? 1 : 0;
            } else {
                validRecords++;
                validEvaluated++;
                if (engineRaisedUnexpectedException(l, actualStatus, actualReasons)) {
                    falsePositives++;
                    addMismatch(mismatches, l, "FALSE_POSITIVE: engine raised an exception on a valid record", actualStatus, actualReasons,
                            actualPath, actualReimbursement);
                }
            }
            long[] bucket = byIssue.computeIfAbsent(l.issueType(), k -> new long[6]);
            issueIsAnomaly.put(l.issueType(), l.anomaly());
            bucket[0]++;
            bucket[1] += outcomeOk ? 1 : 0;
            bucket[2] += evidenceOk ? 1 : 0;
            bucket[3] += pathOk && pathApplicable ? 1 : 0;
            bucket[4] += pathApplicable ? 1 : 0;

            if (l.anomaly() && !detectedHere) {
                addMismatch(mismatches, l, "MISSED_ANOMALY: " + (outcomeOk ? "evidence not found" : "outcome differs"), actualStatus, actualReasons,
                        actualPath, actualReimbursement);
            } else if (!l.anomaly() && !outcomeOk) {
                addMismatch(mismatches, l, "WRONG_OUTCOME on a valid record", actualStatus, actualReasons, actualPath, actualReimbursement);
            } else if (pathApplicable && !pathOk) {
                addMismatch(mismatches, l, "WRONG_APPROVAL_PATH", actualStatus, actualReasons, actualPath, actualReimbursement);
            } else if (claim && !reimbursementOk) {
                addMismatch(mismatches, l, "WRONG_REIMBURSEMENT_STATUS", actualStatus, actualReasons, actualPath, actualReimbursement);
            }
        }

        long unlabelled = leave.keySet().stream().filter(id -> !labelled.contains(id)).count()
                + claims.keySet().stream().filter(id -> !labelled.contains(id)).count();
        long falseNegatives = anomalies - detected;
        double precision = ratio(detected, detected + falsePositives);
        double recall = ratio(detected, anomalies);
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

        Map<String, Long> volumes = new LinkedHashMap<>();
        for (String table : List.of("employee", "department", "cost_center", "employee_cost_center", "leave_policy", "expense_policy",
                "approval_rule", "budget", "employee_policy_assignment", "leave_balance", "leave_balance_transaction", "leave_request",
                "expense_claim", "expense_line", "receipt", "approval_step", "reimbursement", "reimbursement_attempt", "audit_event",
                "decision_trace", "detected_anomaly", "submission_record", "holiday")) {
            volumes.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        volumes.put("ground_truth_labels", (long) labels.size());
        long total = volumes.entrySet().stream().filter(e -> !e.getKey().equals("ground_truth_labels")).mapToLong(Map.Entry::getValue).sum();

        long moreThanOne = reimbursementCounts.values().stream().filter(c -> c > 1).count();
        var integrityChecks = validation.run().checks();
        EvaluationReport.Controls controls = new EvaluationReport.Controls(
                count("SELECT count(*) FROM reimbursement_attempt WHERE outcome <> 'CREATED'"),
                count("SELECT count(*) FROM reimbursement_attempt WHERE outcome = 'DUPLICATE_REJECTED'"),
                count("SELECT count(*) FROM audit_event WHERE event_type = 'APPROVAL_ATTEMPT_REJECTED'"),
                count("SELECT count(*) FROM audit_event WHERE event_type = 'INVALID_TRANSITION_REJECTED'"),
                count("SELECT count(*) FROM submission_record WHERE intake_status <> 'ACCEPTED'"),
                count("SELECT count(*) FROM submission_record WHERE intake_status = 'REJECTED_AT_INTAKE'"),
                findingsOf(integrityChecks, "DI-02"), findingsOf(integrityChecks, "DI-01"));

        List<EvaluationReport.ScenarioBreakdown> scenarios = new ArrayList<>();
        byIssue.forEach((issue, b) -> scenarios.add(new EvaluationReport.ScenarioBreakdown(issue, issueIsAnomaly.get(issue), b[0], b[1], b[2], b[3], b[4])));

        String note = "Metrics compare engine results with ground_truth.label, which the decision engines never read. "
                + "'Detected' = the expected outcome AND the expected reason / evidence were both found. "
                + "False positives = valid records on which the engine raised an exception (violation code or FLAGGED/ON_HOLD/NEEDS_REVIEW) that was not expected.";
        return new EvaluationReport(new EvaluationReport.Meta(properties.businessRulesVersion(), note, labels.size()),
                new EvaluationReport.Totals(volumes, total, validRecords, invalidRecords, unlabelled),
                new EvaluationReport.AnomalyDetection(anomalies, detected, anomalies - detected, falsePositives, falseNegatives,
                        round(precision), round(recall), round(f1), validEvaluated),
                new EvaluationReport.WorkflowDecisions(outcomeEvaluated, outcomeCorrect, outcomeEvaluated - outcomeCorrect,
                        round(ratio(outcomeCorrect, outcomeEvaluated)), pathEvaluated, pathCorrect, pathEvaluated - pathCorrect,
                        round(ratio(pathCorrect, pathEvaluated)), decisionCorrect, decisionTotal - decisionCorrect,
                        round(ratio(decisionCorrect, decisionTotal))),
                new EvaluationReport.ReimbursementDecisions(rmbEvaluated, rmbCorrect, rmbEvaluated - rmbCorrect,
                        round(ratio(rmbCorrect, rmbEvaluated)), moreThanOne),
                controls, scenarios, mismatches);
    }

    /** Does the actual evidence for the expected reason exist? Each evidence type reads a different source. */
    private boolean evidence(Label l, String actualReasons, Actual actual, Map<String, Set<String>> auditSignals,
                             Map<String, Set<String>> attempts, Map<String, Set<String>> anomalyCodes,
                             Map<String, List<String>> ledger, Map<String, Integer> reimbursementCounts, boolean pathOk) {
        String reason = l.reason();
        return switch (l.evidence()) {
            case "NONE" -> true;
            case "DECISION_REASON" -> reason == null || containsCode(actualReasons, reason);
            case "APPROVAL_PATH" -> pathOk;
            case "ANOMALY_RECORD" -> reason != null && anomalyCodes.getOrDefault(l.entityId(), Set.of()).contains(reason);
            case "AUDIT_EVENT" -> {
                Set<String> signals = auditSignals.getOrDefault(l.entityId(), Set.of());
                yield reason == null || (reason.equals("ESCALATED_UNAVAILABLE_APPROVER")
                        ? signals.contains("APPROVER_ESCALATED_UNAVAILABLE")
                        : signals.contains("APPROVAL_ATTEMPT_REJECTED:" + reason) || signals.contains("INVALID_TRANSITION_REJECTED:" + reason));
            }
            case "REIMBURSEMENT_ATTEMPT" -> {
                Set<String> outcomes = attempts.getOrDefault(l.entityId(), Set.of());
                boolean needed = "DUPLICATE_REIMBURSEMENT".equals(reason) ? outcomes.contains("DUPLICATE_REJECTED")
                        : outcomes.contains("NOT_ELIGIBLE_REJECTED");
                yield needed && reimbursementCounts.getOrDefault(l.entityId(), 0) <= 1;
            }
            case "BALANCE_LEDGER" -> {
                List<String> events = ledger.getOrDefault(l.entityId(), List.of());
                yield events.contains("CONSUME") && events.indexOf("RESTORE") > events.indexOf("CONSUME");
            }
            case "INTAKE" -> actual != null && actual.status().equals("REJECTED_AT_INTAKE") && actual.reasons() != null
                    && (reason == null || actual.reasons().contains(reason));
            default -> false;
        };
    }

    /** An engine exception on a record that should have been clean. */
    private boolean engineRaisedUnexpectedException(Label l, String actualStatus, String actualReasons) {
        boolean statusException = EXCEPTION_STATUSES.contains(actualStatus) && !EXCEPTION_STATUSES.contains(l.outcome());
        boolean rejectedByEngine = actualStatus.equals("REJECTED") && !l.outcome().equals("REJECTED");
        boolean violationCode = false;
        for (String code : actualReasons.split(",")) {
            if (!code.isBlank() && !NON_VIOLATION_CODES.contains(code.trim())) {
                violationCode = true;
            }
        }
        boolean expectedCode = l.reason() != null && !l.reason().isBlank();
        return statusException || rejectedByEngine || (violationCode && !expectedCode);
    }

    private static boolean containsCode(String reasons, String code) {
        for (String r : reasons.split(",")) {
            if (r.trim().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private void addMismatch(List<EvaluationReport.Mismatch> list, Label l, String problem, String actualStatus, String actualReasons,
                             String actualPath, String actualReimbursement) {
        if (list.size() < MAX_MISMATCHES) {
            list.add(new EvaluationReport.Mismatch(l.scenarioId(), l.entityType(), l.entityId(), l.issueType(), problem, l.outcome(), actualStatus,
                    l.reason(), actualReasons, l.path(), actualPath, l.reimbursement(), actualReimbursement));
        }
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private static double ratio(long a, long b) {
        return b == 0 ? 1.0 : (double) a / b;
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Persists the report as the latest EVALUATION snapshot and returns it. */
    public EvaluationReport evaluateAndStore() {
        EvaluationReport report = evaluate();
        jdbc.update("INSERT INTO report_snapshot (report_type, generated_at, content) VALUES ('EVALUATION', ?, ?::jsonb)",
                java.sql.Timestamp.from(clock.now()), json.toJson(report));
        return report;
    }

    public java.util.Optional<EvaluationReport> latest() {
        List<String> rows = jdbc.queryForList("SELECT content::text FROM report_snapshot WHERE report_type = 'EVALUATION' ORDER BY report_id DESC LIMIT 1", String.class);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(json.fromJson(rows.get(0), EvaluationReport.class));
    }

    @FunctionalInterface
    private interface RowCallback extends org.springframework.jdbc.core.RowCallbackHandler {
        void row(ResultSet rs) throws java.sql.SQLException;

        @Override
        default void processRow(ResultSet rs) throws java.sql.SQLException {
            row(rs);
        }
    }
}
