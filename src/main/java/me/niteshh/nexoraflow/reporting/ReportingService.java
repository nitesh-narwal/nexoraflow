package me.niteshh.nexoraflow.reporting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operational metrics. Every number is calculated by SQL over the source records (requests, claims, steps,
 * ledger, reimbursements, audit events) - nothing is stored or invented. Money is reported in USD-equivalent
 * using the documented fixed rates of the {@code currency} table.
 */
@Service
public class ReportingService {

    private final JdbcTemplate jdbc;

    public ReportingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Map<String, Object> one(String sql) {
        return jdbc.queryForMap(sql);
    }

    private List<Map<String, Object>> many(String sql) {
        return jdbc.queryForList(sql);
    }

    public Map<String, Object> leaveMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestsByStatus", many("SELECT request_status AS status, count(*) AS count FROM leave_request GROUP BY 1 ORDER BY 1"));
        m.put("totals", one("""
                SELECT count(*) AS total_requests,
                       count(*) FILTER (WHERE request_status = 'APPROVED') AS approved,
                       count(*) FILTER (WHERE request_status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE request_status = 'PENDING_APPROVAL') AS pending,
                       count(*) FILTER (WHERE request_status = 'NEEDS_REVIEW') AS needs_review,
                       count(*) FILTER (WHERE request_status = 'FLAGGED') AS flagged,
                       count(*) FILTER (WHERE request_status = 'CANCELLED') AS cancelled,
                       count(*) FILTER (WHERE decision_reason LIKE '%INSUFFICIENT_BALANCE%') AS insufficient_balance
                FROM leave_request
                """));
        m.put("overlappingLeaveIncidents", one("SELECT count(*) AS incidents FROM detected_anomaly WHERE anomaly_type = 'OVERLAPPING_APPROVED_LEAVE'").get("incidents"));
        m.put("utilizationByDepartment", many("""
                SELECT d.department_name AS department,
                       round(SUM(b.used_balance), 2) AS used_days,
                       round(SUM(b.opening_balance + b.accrued_balance + b.carried_forward_balance), 2) AS granted_days,
                       round(100 * SUM(b.used_balance) / NULLIF(SUM(b.opening_balance + b.accrued_balance + b.carried_forward_balance), 0), 1) AS utilization_percent
                FROM leave_balance b JOIN leave_policy p ON p.leave_policy_id = b.leave_policy_id AND p.leave_type = 'ANNUAL'
                JOIN employee e ON e.employee_id = b.employee_id JOIN department d ON d.department_id = e.department_id
                GROUP BY d.department_name ORDER BY d.department_name
                """));
        m.put("averageApprovalTimeHours", one("""
                SELECT round(AVG(EXTRACT(EPOCH FROM (f.last_action - r.submitted_at)) / 3600)::numeric, 1) AS hours
                FROM leave_request r JOIN (SELECT leave_request_id, MAX(action_at) AS last_action FROM approval_step
                     WHERE approval_status = 'APPROVED' GROUP BY 1) f ON f.leave_request_id = r.leave_request_id
                WHERE r.request_status = 'APPROVED'
                """).get("hours"));
        return m;
    }

    /**
     * Expense metrics. Every claim is counted - also one in a currency the system does not support (it is rejected as
     * INVALID_CURRENCY, which is itself a policy violation worth reporting). Such a claim cannot be converted, so it adds
     * nothing to the USD amounts.
     */
    public Map<String, Object> expenseMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claimsByStatus", many("""
                SELECT c.claim_status AS status, count(*) AS count, round(SUM(c.total_amount * COALESCE(cur.rate_to_reporting, 0)), 2) AS amount_usd
                FROM expense_claim c LEFT JOIN currency cur ON cur.currency_code = c.currency GROUP BY 1 ORDER BY 1
                """));
        m.put("totals", one("""
                SELECT count(*) AS total_claims,
                       round(SUM(c.total_amount * COALESCE(cur.rate_to_reporting, 0)), 2) AS claimed_usd,
                       round(COALESCE(SUM(c.total_amount * COALESCE(cur.rate_to_reporting, 0)) FILTER (WHERE c.claim_status = 'APPROVED'), 0), 2) AS approved_usd,
                       round(COALESCE(SUM(c.total_amount * COALESCE(cur.rate_to_reporting, 0)) FILTER (WHERE c.claim_status = 'REJECTED'), 0), 2) AS rejected_usd,
                       count(*) FILTER (WHERE c.claim_status = 'PENDING_APPROVAL') AS awaiting_approval,
                       count(*) FILTER (WHERE c.decision_reason LIKE '%POLICY_LIMIT_EXCEEDED%' OR c.decision_reason LIKE '%OUTSIDE_EMPLOYMENT_PERIOD%'
                                          OR c.decision_reason LIKE '%INVALID_CURRENCY%' OR c.decision_reason LIKE '%POLICY_NOT_EFFECTIVE%') AS policy_violations,
                       count(*) FILTER (WHERE c.decision_reason LIKE '%RECEIPT_MISSING%') AS missing_receipts,
                       count(*) FILTER (WHERE c.decision_reason LIKE '%DUPLICATE_RECEIPT%') AS duplicate_receipts,
                       count(*) FILTER (WHERE c.decision_reason LIKE '%SUSPICIOUS_SPLIT_CLAIM%') AS suspicious_split_claims
                FROM expense_claim c LEFT JOIN currency cur ON cur.currency_code = c.currency
                """));
        m.put("distributionByCategory", many("""
                SELECT l.expense_category AS category, count(*) AS lines, round(SUM(l.amount * COALESCE(cur.rate_to_reporting, 0)), 2) AS amount_usd
                FROM expense_line l LEFT JOIN currency cur ON cur.currency_code = l.currency WHERE l.amount > 0
                GROUP BY 1 ORDER BY 3 DESC
                """));
        m.put("budgetUtilizationByCostCenter", many("""
                SELECT cc.cost_center_code AS cost_center, b.currency,
                       round(SUM(b.allocated_amount), 2) AS allocated,
                       round(COALESCE(SUM(s.approved), 0), 2) AS approved_spend,
                       round(COALESCE(SUM(s.pending), 0), 2) AS pending_commitment,
                       round(100 * COALESCE(SUM(s.approved), 0) / NULLIF(SUM(b.allocated_amount), 0), 1) AS approved_utilization_percent
                FROM budget b JOIN cost_center cc ON cc.cost_center_id = b.cost_center_id
                LEFT JOIN LATERAL (
                    SELECT SUM(c.total_amount * rc.rate_to_reporting / rb.rate_to_reporting) FILTER (WHERE c.claim_status = 'APPROVED') AS approved,
                           SUM(c.total_amount * rc.rate_to_reporting / rb.rate_to_reporting) FILTER (WHERE c.claim_status = 'PENDING_APPROVAL') AS pending
                    FROM expense_claim c JOIN currency rc ON rc.currency_code = c.currency JOIN currency rb ON rb.currency_code = b.currency
                    WHERE c.cost_center_id = b.cost_center_id AND c.claim_date BETWEEN b.period_start AND b.period_end) s ON TRUE
                GROUP BY cc.cost_center_code, b.currency ORDER BY approved_utilization_percent DESC NULLS LAST, cc.cost_center_code LIMIT 25
                """));
        return m;
    }

    public Map<String, Object> approvalMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepsByStatus", many("SELECT approval_status AS status, count(*) AS count FROM approval_step GROUP BY 1 ORDER BY 1"));
        m.put("totals", one("""
                SELECT count(*) AS approval_steps,
                       count(*) FILTER (WHERE approval_status = 'PENDING') AS pending_approvals,
                       count(*) FILTER (WHERE approval_status = 'REJECTED') AS rejected_approvals,
                       round(AVG(EXTRACT(EPOCH FROM (action_at - assigned_at)) / 3600) FILTER (WHERE approval_status IN ('APPROVED', 'REJECTED'))::numeric, 1) AS average_approval_time_hours
                FROM approval_step
                """));
        m.put("multiLevelApproval", one("""
                SELECT count(*) FILTER (WHERE steps >= 2) AS multi_level_requests, count(*) AS routed_requests,
                       round(100.0 * count(*) FILTER (WHERE steps >= 2) / NULLIF(count(*), 0), 1) AS multi_level_percent
                FROM (SELECT COALESCE(leave_request_id, expense_claim_id) AS rid, count(*) FILTER (WHERE approval_status <> 'SKIPPED') AS steps
                      FROM approval_step GROUP BY 1) t
                """));
        m.put("unauthorizedApprovalAttempts", one("SELECT count(*) AS attempts FROM audit_event WHERE event_type = 'APPROVAL_ATTEMPT_REJECTED'").get("attempts"));
        m.put("attemptsByReason", many("SELECT reason, count(*) AS count FROM audit_event WHERE event_type IN ('APPROVAL_ATTEMPT_REJECTED', 'INVALID_TRANSITION_REJECTED') GROUP BY 1 ORDER BY 2 DESC"));
        m.put("escalations", one("SELECT count(*) AS escalations FROM audit_event WHERE event_type = 'APPROVER_ESCALATED_UNAVAILABLE'").get("escalations"));
        return m;
    }

    public Map<String, Object> financialMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reimbursements", many("""
                SELECT r.reimbursement_status AS status, count(*) AS count, round(SUM(r.approved_amount * cur.rate_to_reporting), 2) AS amount_usd
                FROM reimbursement r JOIN currency cur ON cur.currency_code = r.currency GROUP BY 1 ORDER BY 1
                """));
        m.put("totals", one("""
                SELECT round(COALESCE(SUM(r.approved_amount * cur.rate_to_reporting), 0), 2) AS eligible_reimbursement_usd,
                       round(COALESCE(SUM(r.approved_amount * cur.rate_to_reporting) FILTER (WHERE r.reimbursement_status = 'PENDING_PAYMENT'), 0), 2) AS pending_reimbursement_usd,
                       round(COALESCE(SUM(r.approved_amount * cur.rate_to_reporting) FILTER (WHERE r.reimbursement_status = 'PAID'), 0), 2) AS processed_reimbursement_usd
                FROM reimbursement r JOIN currency cur ON cur.currency_code = r.currency
                """));
        m.put("reimbursementAttempts", many("SELECT outcome, count(*) AS count FROM reimbursement_attempt GROUP BY 1 ORDER BY 1"));
        m.put("duplicateReimbursementAttempts", one("SELECT count(*) AS attempts FROM reimbursement_attempt WHERE outcome = 'DUPLICATE_REJECTED'").get("attempts"));
        m.put("budgetConsumption", one("""
                SELECT round(SUM(b.allocated_amount * rb.rate_to_reporting), 2) AS allocated_usd,
                       round(COALESCE(SUM(s.approved), 0), 2) AS approved_spend_usd,
                       round(100 * COALESCE(SUM(s.approved), 0) / NULLIF(SUM(b.allocated_amount * rb.rate_to_reporting), 0), 2) AS utilization_percent
                FROM budget b JOIN currency rb ON rb.currency_code = b.currency
                LEFT JOIN LATERAL (SELECT SUM(c.total_amount * rc.rate_to_reporting) AS approved FROM expense_claim c JOIN currency rc ON rc.currency_code = c.currency
                                   WHERE c.cost_center_id = b.cost_center_id AND c.claim_status = 'APPROVED' AND c.claim_date BETWEEN b.period_start AND b.period_end) s ON TRUE
                """));
        return m;
    }

    /** Exception report: what the engines held back or rejected, and why. */
    public Map<String, Object> exceptionMetrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("openExceptions", many("""
                SELECT 'LEAVE_REQUEST' AS entity_type, request_status AS status, count(*) AS count FROM leave_request
                WHERE request_status IN ('FLAGGED', 'NEEDS_REVIEW') GROUP BY 2
                UNION ALL
                SELECT 'EXPENSE_CLAIM', claim_status, count(*) FROM expense_claim WHERE claim_status IN ('FLAGGED', 'NEEDS_REVIEW', 'ON_HOLD') GROUP BY 2
                ORDER BY 1, 2
                """));
        m.put("decisionsByReasonCode", many("""
                SELECT code, entity_type, count(*) AS count FROM (
                    SELECT unnest(string_to_array(decision_reason, ',')) AS code, 'LEAVE_REQUEST' AS entity_type FROM leave_request WHERE decision_reason IS NOT NULL
                    UNION ALL
                    SELECT unnest(string_to_array(decision_reason, ',')), 'EXPENSE_CLAIM' FROM expense_claim WHERE decision_reason IS NOT NULL) x
                GROUP BY 1, 2 ORDER BY 3 DESC, 1
                """));
        m.put("detectedAnomaliesByType", many("SELECT anomaly_type, severity, count(*) AS count FROM detected_anomaly GROUP BY 1, 2 ORDER BY 3 DESC, 1"));
        m.put("submissionsPreserved", many("SELECT request_type, intake_status, count(*) AS count FROM submission_record WHERE intake_status <> 'ACCEPTED' GROUP BY 1, 2 ORDER BY 1, 2"));
        return m;
    }

    public Map<String, Object> summary() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("leave", leaveMetrics());
        all.put("expense", expenseMetrics());
        all.put("approvals", approvalMetrics());
        all.put("financial", financialMetrics());
        all.put("exceptions", exceptionMetrics());
        return all;
    }
}
