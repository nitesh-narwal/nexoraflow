package me.niteshh.nexoraflow.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.approval.ApprovalRule;
import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApproverRole;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.lifecycle.StateMachine;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.costcenter.CostCenterStatus;
import me.niteshh.nexoraflow.employee.AssignmentType;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.expense.EligibilityStatus;
import me.niteshh.nexoraflow.expense.ExpenseClaimStatus;
import me.niteshh.nexoraflow.expense.LineStatus;
import me.niteshh.nexoraflow.export.TableCatalog;
import me.niteshh.nexoraflow.leave.BalanceTransactionType;
import me.niteshh.nexoraflow.leave.LeaveRequestStatus;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.LeaveType;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;
import me.niteshh.nexoraflow.policy.PolicyStatus;
import me.niteshh.nexoraflow.reimbursement.AttemptOutcome;
import me.niteshh.nexoraflow.reimbursement.ReimbursementStatus;

/**
 * Dedicated validation module: database integrity, business integrity and data quality. It reads the database
 * directly (SQL), independent of the services that produced the data, and reports WHICH records fail each check.
 * It also tells apart deliberately invalid SUBMISSIONS (preserved on purpose) from corrupted CANONICAL records.
 */
@Service
public class ValidationService {

    private static final int SAMPLE = 25;

    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final AppClock clock;

    public ValidationService(JdbcTemplate jdbc, JsonSupport json, AppClock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public ValidationReport run() {
        List<ValidationReport.CheckResult> checks = new ArrayList<>();
        // ---- database integrity
        checks.add(check("DI-01", "DATABASE_INTEGRITY", "Primary-key uniqueness", "Every table has unique primary keys (business identifiers).", "ERROR", this::primaryKeys));
        checks.add(check("DI-02", "DATABASE_INTEGRITY", "Foreign-key integrity", "Every foreign key references an existing parent record.", "ERROR", this::foreignKeys));
        checks.add(check("DI-03", "DATABASE_INTEGRITY", "Business identifier format", "IDs follow the documented conventions (EMP-000001, LVE-2026-000001, ...).", "ERROR", this::identifierFormats));
        checks.add(check("DI-04", "DATABASE_INTEGRITY", "Unique business attributes", "Employee e-mail, cost-center code and payment reference are unique.", "ERROR", this::uniqueAttributes));
        checks.add(check("DI-05", "DATABASE_INTEGRITY", "Required fields", "Core descriptive fields are present and not blank.", "ERROR", this::requiredFields));
        checks.add(check("DI-06", "DATABASE_INTEGRITY", "Valid enum values", "Status / type columns only contain values of the application enums.", "ERROR", this::enumValues));
        checks.add(check("DI-07", "DATABASE_INTEGRITY", "Valid date ranges", "Start <= end for employment, policies, assignments, budgets and canonical requests.", "ERROR", this::dateRanges));
        // ---- business integrity
        checks.add(check("BI-01", "BUSINESS_INTEGRITY", "Claim total equals the sum of its lines", "total_amount = sum of positive line amounts.", "ERROR", this::claimTotals));
        checks.add(check("BI-02", "BUSINESS_INTEGRITY", "Leave balances reconcile with the ledger", "Each balance component equals the sum of its ledger deltas; available = opening + accrued + carried - used - pending.", "ERROR", this::balanceReconciliation));
        checks.add(check("BI-03", "BUSINESS_INTEGRITY", "Approved leave consumes balance exactly once", "Every APPROVED request has exactly one CONSUME and a net used amount equal to its business days.", "ERROR", this::approvedConsumesOnce));
        checks.add(check("BI-04", "BUSINESS_INTEGRITY", "Rejected / cancelled / unapproved leave does not consume balance", "Non-approved requests have no net used days; only pending requests hold a reservation.", "ERROR", this::nonApprovedDoesNotConsume));
        checks.add(check("BI-05", "BUSINESS_INTEGRITY", "Approval routes follow configured thresholds", "The levels and roles of every approval route equal those prescribed by the active approval rules.", "ERROR", this::routeConformance));
        checks.add(check("BI-06", "BUSINESS_INTEGRITY", "Approval actors possess authority", "Actors were active, are not the requester, and hold the role's authority and reporting-line relationship.", "ERROR", this::approverAuthority));
        checks.add(check("BI-07", "BUSINESS_INTEGRITY", "Reimbursement only for eligible, finally approved claims", "Every reimbursement belongs to an APPROVED + ELIGIBLE claim with all steps complete and matching amount; every APPROVED claim has one.", "ERROR", this::reimbursementEligibility));
        checks.add(check("BI-08", "BUSINESS_INTEGRITY", "Duplicate reimbursement prevented", "At most one reimbursement per claim; rejected duplicate attempts are recorded.", "ERROR", this::duplicateReimbursement));
        checks.add(check("BI-09", "BUSINESS_INTEGRITY", "Budget consumption reconciles with approved expenses", "Approved spend per budget equals the sum of reimbursements and never exceeds the allocation.", "ERROR", this::budgetReconciliation));
        checks.add(check("BI-10", "BUSINESS_INTEGRITY", "Lifecycle transitions are valid", "Audit trails replay as valid, continuous state-machine paths ending in the current state.", "ERROR", this::lifecycleTransitions));
        // ---- data quality
        checks.add(check("DQ-01", "DATA_QUALITY", "Valid organisational hierarchy", "One root, no cycles, no self-management, managers rank above reports.", "ERROR", this::hierarchy));
        checks.add(check("DQ-02", "DATA_QUALITY", "No unexplained negative amounts", "Negative / zero amounts exist only on explicitly INVALID lines.", "ERROR", this::negativeAmounts));
        checks.add(check("DQ-03", "DATA_QUALITY", "Valid employment dates", "Termination date only for TERMINATED employees and never before joining.", "ERROR", this::employmentDates));
        checks.add(check("DQ-04", "DATA_QUALITY", "Consistent currencies", "Line, claim, reimbursement, policy and budget currencies agree (explained INVALID_CURRENCY claims excepted).", "ERROR", this::currencies));
        checks.add(check("DQ-05", "DATA_QUALITY", "No unexpected duplicate receipts", "A receipt hash reused by live claims is always explained by a detected DUPLICATE_RECEIPT anomaly.", "ERROR", this::unexpectedDuplicateReceipts));
        checks.add(check("DQ-06", "DATA_QUALITY", "No overlapping approved leave", "Detects corrupted canonical data: two APPROVED requests of one employee that overlap.", "ERROR", this::overlappingApprovedLeave));
        checks.add(check("DQ-07", "DATA_QUALITY", "Canonical records never carry invalid references", "Claims without a cost center only exist in non-live states and keep the submitted value.", "ERROR", this::canonicalReferences));
        checks.add(check("DQ-08", "DATA_QUALITY", "Deliberately invalid submissions preserved (informational)", "Submissions with invalid references or inverted dates are kept in staging / as rejected records - this is expected, not corruption.", "INFO", this::preservedInvalidSubmissions));

        long failed = checks.stream().filter(c -> c.status().equals("FAIL")).count();
        long info = checks.stream().filter(c -> c.status().equals("INFO")).count();
        long findings = checks.stream().filter(c -> !c.severity().equals("INFO")).mapToLong(ValidationReport.CheckResult::findingCount).sum();
        ValidationReport.Summary summary = new ValidationReport.Summary(checks.size(), (int) (checks.size() - failed - info), (int) failed,
                (int) info, findings, failed == 0);
        return new ValidationReport(summary, checks);
    }

    public ValidationReport runAndStore() {
        ValidationReport report = run();
        jdbc.update("INSERT INTO report_snapshot (report_type, generated_at, content) VALUES ('VALIDATION', ?, ?::jsonb)",
                java.sql.Timestamp.from(clock.now()), json.toJson(report));
        return report;
    }

    public Optional<ValidationReport> latest() {
        List<String> rows = jdbc.queryForList("SELECT content::text FROM report_snapshot WHERE report_type = 'VALIDATION' ORDER BY report_id DESC LIMIT 1", String.class);
        return rows.isEmpty() ? Optional.empty() : Optional.of(json.fromJson(rows.get(0), ValidationReport.class));
    }

    // ------------------------------------------------------------------ plumbing

    private ValidationReport.CheckResult check(String id, String category, String name, String description, String severity,
                                               Supplier<List<ValidationReport.Finding>> supplier) {
        List<ValidationReport.Finding> all = supplier.get();
        String status = all.isEmpty() ? "PASS" : severity.equals("INFO") ? "INFO" : "FAIL";
        return new ValidationReport.CheckResult(id, category, name, description, severity, status, all.size(),
                all.size() > SAMPLE ? new ArrayList<>(all.subList(0, SAMPLE)) : all);
    }

    private List<ValidationReport.Finding> sql(String entityType, String sql, Object... args) {
        List<ValidationReport.Finding> out = new ArrayList<>();
        jdbc.query(sql, rs -> {
            out.add(new ValidationReport.Finding(entityType, rs.getString(1), rs.getString(2)));
        }, args);
        return out;
    }

    // ------------------------------------------------------------------ database integrity

    private List<ValidationReport.Finding> primaryKeys() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        for (TableCatalog.ExportTable t : TableCatalog.TABLES) {
            String pk = t.primaryKeyColumn();
            out.addAll(sql(t.table().toUpperCase(), "SELECT " + pk + ", 'duplicate primary key' FROM " + t.table()
                    + " GROUP BY " + pk + " HAVING count(*) > 1"));
        }
        return out;
    }

    private record ForeignKey(String name, String childTable, String childColumn, String parentTable, String parentColumn) {
    }

    private List<ValidationReport.Finding> foreignKeys() {
        List<ForeignKey> fks = jdbc.query("""
                SELECT con.conname, cl.relname,
                       (SELECT att.attname FROM pg_attribute att WHERE att.attrelid = con.conrelid AND att.attnum = con.conkey[1]),
                       par.relname,
                       (SELECT att.attname FROM pg_attribute att WHERE att.attrelid = con.confrelid AND att.attnum = con.confkey[1])
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid JOIN pg_namespace n ON n.oid = cl.relnamespace
                JOIN pg_class par ON par.oid = con.confrelid
                WHERE con.contype = 'f' AND n.nspname = 'public' ORDER BY con.conname
                """, (rs, i) -> new ForeignKey(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
        List<ValidationReport.Finding> out = new ArrayList<>();
        for (ForeignKey fk : fks) {
            String pk = TableCatalog.TABLES.stream().filter(t -> t.table().equals(fk.childTable())).map(TableCatalog.ExportTable::primaryKeyColumn)
                    .findFirst().orElse(fk.childColumn());
            out.addAll(sql(fk.childTable().toUpperCase(), "SELECT c." + pk + ", 'orphan ' || '" + fk.childColumn() + "=' || c." + fk.childColumn()
                    + " || ' -> " + fk.parentTable() + " (" + fk.name() + ")' FROM " + fk.childTable() + " c LEFT JOIN " + fk.parentTable()
                    + " p ON p." + fk.parentColumn() + " = c." + fk.childColumn() + " WHERE c." + fk.childColumn() + " IS NOT NULL AND p."
                    + fk.parentColumn() + " IS NULL"));
        }
        return out;
    }

    private List<ValidationReport.Finding> identifierFormats() {
        Map<String, String[]> formats = new LinkedHashMap<>();
        formats.put("employee|employee_id", new String[] {"^EMP-[0-9]{6,}$"});
        formats.put("leave_request|leave_request_id", new String[] {"^LVE-[0-9]{4}-[0-9]{6}$"});
        formats.put("expense_claim|expense_claim_id", new String[] {"^EXP-[0-9]{4}-[0-9]{6}$"});
        formats.put("expense_line|expense_line_id", new String[] {"^EXPL-EXP-[0-9]{4}-[0-9]{6}-[0-9]{2}$"});
        formats.put("approval_step|approval_step_id", new String[] {"^APR-(LVE|EXP)-[0-9]{4}-[0-9]{6}-[0-9]{2}$"});
        formats.put("reimbursement|reimbursement_id", new String[] {"^RMB-[0-9]{4}-[0-9]{6}$"});
        formats.put("receipt|receipt_id", new String[] {"^RCP-[0-9]{6}$"});
        formats.put("audit_event|event_id", new String[] {"^EVT-[0-9]{8}$"});
        formats.put("submission_record|submission_id", new String[] {"^SUB-[0-9]{6}$"});
        formats.put("leave_balance|leave_balance_id", new String[] {"^LBAL-[0-9]{6}$"});
        formats.put("department|department_id", new String[] {"^DEP-[A-Z]+$"});
        formats.put("cost_center|cost_center_id", new String[] {"^CC-[0-9]{4}$"});
        formats.put("leave_policy|leave_policy_id", new String[] {"^LPOL-[A-Z-]+$"});
        formats.put("expense_policy|expense_policy_id", new String[] {"^XPOL-[A-Z_]+-[A-Z]{2}(-V[0-9]+)?$"});
        formats.put("approval_rule|approval_rule_id", new String[] {"^AR-(LV|EX)-[0-9]{3}$"});
        List<ValidationReport.Finding> out = new ArrayList<>();
        formats.forEach((key, pattern) -> {
            String[] parts = key.split("\\|");
            out.addAll(sql(parts[0].toUpperCase(), "SELECT " + parts[1] + ", 'identifier does not match ' || ? FROM " + parts[0]
                    + " WHERE " + parts[1] + " !~ ?", pattern[0], pattern[0]));
        });
        return out;
    }

    private List<ValidationReport.Finding> uniqueAttributes() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EMPLOYEE", "SELECT lower(email), 'duplicate e-mail' FROM employee GROUP BY lower(email) HAVING count(*) > 1"));
        out.addAll(sql("COST_CENTER", "SELECT cost_center_code, 'duplicate cost-center code' FROM cost_center GROUP BY cost_center_code HAVING count(*) > 1"));
        out.addAll(sql("REIMBURSEMENT", "SELECT payment_reference, 'duplicate payment reference' FROM reimbursement WHERE payment_reference IS NOT NULL GROUP BY payment_reference HAVING count(*) > 1"));
        return out;
    }

    private List<ValidationReport.Finding> requiredFields() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EMPLOYEE", "SELECT employee_id, 'blank name / e-mail / designation' FROM employee WHERE btrim(first_name) = '' OR btrim(last_name) = '' OR btrim(email) = '' OR btrim(designation) = ''"));
        out.addAll(sql("EXPENSE_LINE", "SELECT expense_line_id, 'blank merchant' FROM expense_line WHERE btrim(merchant_name) = ''"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'claim without any line item' FROM expense_claim c WHERE NOT EXISTS (SELECT 1 FROM expense_line l WHERE l.expense_claim_id = c.expense_claim_id) AND claim_status <> 'REJECTED'"));
        out.addAll(sql("LEAVE_REQUEST", "SELECT leave_request_id, 'missing decision on a decided request' FROM leave_request WHERE request_status IN ('APPROVED','REJECTED') AND decision IS NULL"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'missing decision on a decided claim' FROM expense_claim WHERE claim_status IN ('APPROVED','REJECTED') AND decision IS NULL"));
        return out;
    }

    private List<ValidationReport.Finding> enumValues() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        Object[][] checks = {
                {"employee", "employee_id", "employment_type", EmploymentType.class}, {"employee", "employee_id", "employment_status", EmploymentStatus.class},
                {"cost_center", "cost_center_id", "status", CostCenterStatus.class}, {"employee_cost_center", "assignment_id", "assignment_type", AssignmentType.class},
                {"leave_policy", "leave_policy_id", "leave_type", LeaveType.class}, {"leave_policy", "leave_policy_id", "accrual_frequency", AccrualFrequency.class},
                {"leave_policy", "leave_policy_id", "policy_status", PolicyStatus.class}, {"expense_policy", "expense_policy_id", "expense_category", ExpenseCategory.class},
                {"expense_policy", "expense_policy_id", "employee_type", PolicyEmployeeType.class}, {"expense_policy", "expense_policy_id", "policy_status", PolicyStatus.class},
                {"approval_rule", "approval_rule_id", "workflow_type", RequestType.class}, {"approval_rule", "approval_rule_id", "approver_role", ApproverRole.class},
                {"approval_rule", "approval_rule_id", "rule_status", ApprovalRule.Status.class}, {"leave_request", "leave_request_id", "request_status", LeaveRequestStatus.class},
                {"leave_request", "leave_request_id", "decision", Decision.class}, {"expense_claim", "expense_claim_id", "claim_status", ExpenseClaimStatus.class},
                {"expense_claim", "expense_claim_id", "eligibility_status", EligibilityStatus.class}, {"expense_claim", "expense_claim_id", "decision", Decision.class},
                {"expense_line", "expense_line_id", "line_status", LineStatus.class}, {"expense_line", "expense_line_id", "expense_category", ExpenseCategory.class},
                {"approval_step", "approval_step_id", "approval_status", ApprovalStatus.class}, {"approval_step", "approval_step_id", "approver_role", ApproverRole.class},
                {"approval_step", "approval_step_id", "request_type", RequestType.class}, {"reimbursement", "reimbursement_id", "reimbursement_status", ReimbursementStatus.class},
                {"reimbursement_attempt", "attempt_id", "outcome", AttemptOutcome.class}, {"leave_balance_transaction", "txn_id", "txn_type", BalanceTransactionType.class}};
        for (Object[] c : checks) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum<?>> type = (Class<? extends Enum<?>>) c[3];
            List<String> values = new ArrayList<>();
            for (Enum<?> e : type.getEnumConstants()) {
                values.add(e.name());
            }
            String list = "'" + String.join("','", values) + "'";
            out.addAll(sql(((String) c[0]).toUpperCase(), "SELECT " + c[1] + ", '" + c[2] + "=' || " + c[2] + " || ' is not a valid " + type.getSimpleName()
                    + "' FROM " + c[0] + " WHERE " + c[2] + " IS NOT NULL AND " + c[2] + " NOT IN (" + list + ")"));
        }
        return out;
    }

    private List<ValidationReport.Finding> dateRanges() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("LEAVE_REQUEST", "SELECT leave_request_id, 'start_date after end_date on a non-rejected request' FROM leave_request WHERE start_date > end_date AND request_status <> 'REJECTED'"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'expense period inverted on a non-rejected claim' FROM expense_claim WHERE expense_period_start > expense_period_end AND claim_status <> 'REJECTED'"));
        out.addAll(sql("LEAVE_POLICY", "SELECT leave_policy_id, 'effective_to before effective_from' FROM leave_policy WHERE effective_to < effective_from"));
        out.addAll(sql("EXPENSE_POLICY", "SELECT expense_policy_id, 'effective_to before effective_from' FROM expense_policy WHERE effective_to < effective_from"));
        out.addAll(sql("APPROVAL_RULE", "SELECT approval_rule_id, 'effective_to before effective_from' FROM approval_rule WHERE effective_to < effective_from"));
        out.addAll(sql("EMPLOYEE_COST_CENTER", "SELECT assignment_id, 'effective_to before effective_from' FROM employee_cost_center WHERE effective_to < effective_from"));
        out.addAll(sql("BUDGET", "SELECT budget_id, 'period_end before period_start' FROM budget WHERE period_end < period_start"));
        out.addAll(sql("EXPENSE_LINE", "SELECT l.expense_line_id, 'transaction after the claim submission date' FROM expense_line l JOIN expense_claim c ON c.expense_claim_id = l.expense_claim_id WHERE l.transaction_date > c.submission_date AND l.line_status NOT IN ('INVALID')"));
        return out;
    }

    // ------------------------------------------------------------------ business integrity

    private List<ValidationReport.Finding> claimTotals() {
        return sql("EXPENSE_CLAIM", """
                SELECT c.expense_claim_id, 'total ' || c.total_amount || ' <> sum of lines ' || COALESCE(s.total, 0)
                FROM expense_claim c LEFT JOIN (SELECT expense_claim_id, SUM(amount) FILTER (WHERE amount > 0) AS total FROM expense_line GROUP BY 1) s
                  ON s.expense_claim_id = c.expense_claim_id
                WHERE c.total_amount <> COALESCE(s.total, 0)
                """);
    }

    private List<ValidationReport.Finding> balanceReconciliation() {
        return sql("LEAVE_BALANCE", """
                SELECT b.leave_balance_id, 'components differ from ledger sums or available is not derived'
                FROM leave_balance b LEFT JOIN (SELECT leave_balance_id, SUM(opening_delta) o, SUM(accrued_delta) a, SUM(carried_forward_delta) c,
                       SUM(used_delta) u, SUM(pending_delta) p FROM leave_balance_transaction GROUP BY 1) t ON t.leave_balance_id = b.leave_balance_id
                WHERE b.opening_balance <> COALESCE(t.o, 0) OR b.accrued_balance <> COALESCE(t.a, 0) OR b.carried_forward_balance <> COALESCE(t.c, 0)
                   OR b.used_balance <> COALESCE(t.u, 0) OR b.pending_balance <> COALESCE(t.p, 0)
                   OR b.available_balance <> b.opening_balance + b.accrued_balance + b.carried_forward_balance - b.used_balance - b.pending_balance
                """);
    }

    private static final String LEAVE_LEDGER = """
            SELECT r.leave_request_id, r.request_status, COALESCE(r.calculated_business_days, 0) AS days,
                   COALESCE(SUM(t.used_delta), 0) AS used_net, COALESCE(SUM(t.pending_delta), 0) AS pending_net,
                   COUNT(*) FILTER (WHERE t.txn_type = 'CONSUME') AS consumes
            FROM leave_request r LEFT JOIN leave_balance_transaction t ON t.leave_request_id = r.leave_request_id
            GROUP BY r.leave_request_id, r.request_status, r.calculated_business_days
            """;

    private List<ValidationReport.Finding> approvedConsumesOnce() {
        return sql("LEAVE_REQUEST", "SELECT leave_request_id, 'APPROVED request has consumes=' || consumes || ', used_net=' || used_net || ', pending_net=' || pending_net || ' (days ' || days || ')' FROM ("
                + LEAVE_LEDGER + ") x WHERE request_status = 'APPROVED' AND (consumes <> 1 OR used_net <> days OR pending_net <> 0)");
    }

    private List<ValidationReport.Finding> nonApprovedDoesNotConsume() {
        return sql("LEAVE_REQUEST", "SELECT leave_request_id, request_status || ' request has used_net=' || used_net || ', pending_net=' || pending_net || ' (days ' || days || ')' FROM ("
                + LEAVE_LEDGER + ") x WHERE request_status <> 'APPROVED' AND (used_net <> 0 OR (request_status = 'PENDING_APPROVAL' AND pending_net <> days) "
                + "OR (request_status <> 'PENDING_APPROVAL' AND pending_net <> 0))");
    }

    /** Amounts are exact decimals: money is never compared as a floating-point number. */
    private record Rule(String id, String type, String country, String dept, String category, java.math.BigDecimal min,
                        java.math.BigDecimal max, int level, String role, int priority, String from, String to) {
    }

    /**
     * Independent re-derivation of every approval route from the configured rules. The expense amount is the claim total
     * converted to the reporting currency and rounded to cents - the documented definition - so that an amount of
     * 500.004 USD (500.00 after rounding) is not treated as being above the 500.00 boundary.
     */
    private List<ValidationReport.Finding> routeConformance() {
        List<Rule> rules = jdbc.query("""
                SELECT approval_rule_id, workflow_type, country, department_id, expense_category, minimum_amount, maximum_amount, approval_level,
                       approver_role, priority, effective_from::text, effective_to::text
                FROM approval_rule WHERE rule_status = 'ACTIVE'
                """, (rs, i) -> new Rule(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBigDecimal(6),
                rs.getBigDecimal(7), rs.getInt(8), rs.getString(9), rs.getInt(10), rs.getString(11), rs.getString(12)));
        Map<String, Set<String>> actual = new HashMap<>();
        jdbc.query("SELECT COALESCE(leave_request_id, expense_claim_id), approval_level || ':' || approver_role FROM approval_step WHERE approval_status <> 'ESCALATED'",
                rs -> {
                    actual.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                });
        List<ValidationReport.Finding> out = new ArrayList<>();
        jdbc.query("""
                SELECT r.leave_request_id, e.country, e.department_id, r.calculated_business_days, r.submission_date::text, NULL AS categories
                FROM leave_request r JOIN employee e ON e.employee_id = r.employee_id WHERE r.calculated_business_days IS NOT NULL
                """, rs -> {
            checkRoute(out, rules, actual, "LEAVE_REQUEST", "LEAVE", rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), Set.of());
        });
        jdbc.query("""
                SELECT c.expense_claim_id, e.country, e.department_id, round(c.total_amount * cur.rate_to_reporting, 2), c.claim_date::text,
                       (SELECT string_agg(DISTINCT l.expense_category, ',') FROM expense_line l WHERE l.expense_claim_id = c.expense_claim_id)
                FROM expense_claim c JOIN employee e ON e.employee_id = c.employee_id JOIN currency cur ON cur.currency_code = c.currency
                """, rs -> {
            String cats = rs.getString(6);
            checkRoute(out, rules, actual, "EXPENSE_CLAIM", "EXPENSE", rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5),
                    cats == null ? Set.of() : Set.of(cats.split(",")));
        });
        return out;
    }

    private void checkRoute(List<ValidationReport.Finding> out, List<Rule> rules, Map<String, Set<String>> actual, String entityType, String type,
                            String id, String country, String dept, java.math.BigDecimal amount, String date, Set<String> categories) {
        Set<String> steps = actual.get(id);
        if (steps == null) {
            return; // requests that never entered approval have no route to verify
        }
        Map<Integer, Rule> best = new java.util.TreeMap<>();
        for (Rule r : rules) {
            boolean applies = r.type().equals(type) && (r.country() == null || r.country().equals(country)) && (r.dept() == null || r.dept().equals(dept))
                    && (r.category() == null || categories.contains(r.category())) && amount.compareTo(r.min()) > 0 && (r.max() == null || amount.compareTo(r.max()) <= 0)
                    && r.from().compareTo(date) <= 0 && (r.to() == null || r.to().compareTo(date) >= 0);
            if (!applies) {
                continue;
            }
            Rule current = best.get(r.level());
            if (current == null || r.priority() < current.priority() || (r.priority() == current.priority() && specificity(r) > specificity(current))
                    || (r.priority() == current.priority() && specificity(r) == specificity(current) && r.id().compareTo(current.id()) < 0)) {
                best.put(r.level(), r);
            }
        }
        Set<String> expected = new HashSet<>();
        best.forEach((level, r) -> expected.add(level + ":" + r.role()));
        if (!expected.equals(steps)) {
            out.add(new ValidationReport.Finding(entityType, id, "route " + steps + " does not match the configured rules " + expected + " for amount " + amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        }
    }

    private static int specificity(Rule r) {
        return (r.country() != null ? 1 : 0) + (r.dept() != null ? 1 : 0) + (r.category() != null ? 1 : 0);
    }

    private List<ValidationReport.Finding> approverAuthority() {
        String base = """
                FROM approval_step s
                JOIN employee a ON a.employee_id = s.approver_employee_id
                LEFT JOIN leave_request lr ON lr.leave_request_id = s.leave_request_id
                LEFT JOIN expense_claim ec ON ec.expense_claim_id = s.expense_claim_id
                JOIN employee req ON req.employee_id = COALESCE(lr.employee_id, ec.employee_id)
                LEFT JOIN approval_rule ar ON ar.approval_rule_id = s.approval_rule_id
                WHERE s.approval_status IN ('APPROVED', 'REJECTED')
                """;
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("APPROVAL_STEP", "SELECT s.approval_step_id, 'approver is the requester' " + base + " AND a.employee_id = req.employee_id"));
        out.addAll(sql("APPROVAL_STEP", "SELECT s.approval_step_id, 'approver was not an active employee at action time' " + base
                + " AND (a.employment_status = 'INACTIVE' OR a.joining_date > s.action_at::date OR (a.termination_date IS NOT NULL AND a.termination_date < s.action_at::date))"));
        out.addAll(sql("APPROVAL_STEP", "SELECT s.approval_step_id, 'approver lacks the job level required for ' || s.approver_role " + base
                + " AND ((s.approver_role = 'MANAGER' AND a.job_level > 3) OR (s.approver_role = 'DEPARTMENT_HEAD' AND a.job_level > 2))"));
        out.addAll(sql("APPROVAL_STEP", "SELECT s.approval_step_id, 'approver designation ' || a.designation || ' <> required ' || ar.approver_designation " + base
                + " AND s.approver_role IN ('FINANCE_REVIEWER','FINANCE_HEAD') AND ar.approver_designation IS NOT NULL AND a.designation <> ar.approver_designation"));
        out.addAll(sql("APPROVAL_STEP", """
                WITH RECURSIVE chain(emp, anc) AS (
                    SELECT employee_id, manager_id FROM employee WHERE manager_id IS NOT NULL
                    UNION SELECT c.emp, e.manager_id FROM chain c JOIN employee e ON e.employee_id = c.anc WHERE e.manager_id IS NOT NULL)
                SELECT s.approval_step_id, 'approver is not in the requester''s reporting line' """ + base
                + " AND s.approver_role IN ('DIRECT_MANAGER','MANAGER','DEPARTMENT_HEAD') AND NOT EXISTS (SELECT 1 FROM chain c WHERE c.emp = req.employee_id AND c.anc = a.employee_id)"));
        return out;
    }

    private List<ValidationReport.Finding> reimbursementEligibility() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("REIMBURSEMENT", """
                SELECT r.reimbursement_id, 'claim ' || c.expense_claim_id || ' is ' || c.claim_status || '/' || c.eligibility_status || ' or has open steps or amount mismatch'
                FROM reimbursement r JOIN expense_claim c ON c.expense_claim_id = r.expense_claim_id
                WHERE c.claim_status <> 'APPROVED' OR c.eligibility_status <> 'ELIGIBLE' OR r.approved_amount <> c.total_amount OR r.currency <> c.currency
                   OR r.employee_id <> c.employee_id
                   OR EXISTS (SELECT 1 FROM approval_step s WHERE s.expense_claim_id = c.expense_claim_id AND s.approval_status IN ('WAITING','PENDING','REJECTED'))
                   OR NOT EXISTS (SELECT 1 FROM approval_step s WHERE s.expense_claim_id = c.expense_claim_id AND s.approval_status = 'APPROVED')
                """));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT c.expense_claim_id, 'APPROVED claim without a reimbursement' FROM expense_claim c LEFT JOIN reimbursement r ON r.expense_claim_id = c.expense_claim_id WHERE c.claim_status = 'APPROVED' AND r.reimbursement_id IS NULL"));
        return out;
    }

    private List<ValidationReport.Finding> duplicateReimbursement() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'more than one reimbursement' FROM reimbursement GROUP BY expense_claim_id HAVING count(*) > 1"));
        out.addAll(sql("REIMBURSEMENT_ATTEMPT", "SELECT a.attempt_id, 'duplicate-rejected attempt without an existing reimbursement' FROM reimbursement_attempt a LEFT JOIN reimbursement r ON r.expense_claim_id = a.expense_claim_id WHERE a.outcome = 'DUPLICATE_REJECTED' AND r.reimbursement_id IS NULL"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'reimbursement created more than once according to the attempt log' FROM reimbursement_attempt WHERE outcome = 'CREATED' GROUP BY expense_claim_id HAVING count(*) > 1"));
        return out;
    }

    private List<ValidationReport.Finding> budgetReconciliation() {
        return sql("BUDGET", """
                SELECT b.budget_id, 'approved from claims ' || round(x.from_claims, 2) || ' vs reimbursements ' || round(x.from_reimbursements, 2)
                       || ' (allocated ' || b.allocated_amount || ' ' || b.currency || ')'
                FROM budget b
                JOIN currency rb ON rb.currency_code = b.currency
                JOIN LATERAL (
                    SELECT COALESCE((SELECT SUM(c.total_amount * rc.rate_to_reporting / rb.rate_to_reporting)
                                     FROM expense_claim c JOIN currency rc ON rc.currency_code = c.currency
                                     WHERE c.cost_center_id = b.cost_center_id AND c.claim_status = 'APPROVED'
                                       AND c.claim_date BETWEEN b.period_start AND b.period_end), 0) AS from_claims,
                           COALESCE((SELECT SUM(r.approved_amount * rr.rate_to_reporting / rb.rate_to_reporting)
                                     FROM reimbursement r JOIN expense_claim c ON c.expense_claim_id = r.expense_claim_id
                                     JOIN currency rr ON rr.currency_code = r.currency
                                     WHERE c.cost_center_id = b.cost_center_id AND c.claim_date BETWEEN b.period_start AND b.period_end), 0) AS from_reimbursements
                ) x ON TRUE
                WHERE abs(x.from_claims - x.from_reimbursements) > 0.05 OR x.from_claims > b.allocated_amount + 0.05
                """);
    }

    // ------------------------------------------------------------------ lifecycle

    private List<ValidationReport.Finding> lifecycleTransitions() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        replay(out, "LEAVE_REQUEST", "SELECT entity_id, previous_state, new_state FROM audit_event WHERE entity_type = 'LEAVE_REQUEST' AND event_type IN ('REQUEST_SUBMITTED','STATUS_CHANGED') ORDER BY entity_id, occurred_at, event_id",
                "SELECT leave_request_id, request_status FROM leave_request", LeaveRequestStatus.LIFECYCLE, LeaveRequestStatus.class, "SUBMITTED");
        replay(out, "EXPENSE_CLAIM", "SELECT entity_id, previous_state, new_state FROM audit_event WHERE entity_type = 'EXPENSE_CLAIM' AND event_type IN ('REQUEST_SUBMITTED','STATUS_CHANGED') ORDER BY entity_id, occurred_at, event_id",
                "SELECT expense_claim_id, claim_status FROM expense_claim", ExpenseClaimStatus.LIFECYCLE, ExpenseClaimStatus.class, "SUBMITTED");
        replay(out, "APPROVAL_STEP", "SELECT entity_id, previous_state, new_state FROM audit_event WHERE entity_type = 'APPROVAL_STEP' ORDER BY entity_id, occurred_at, event_id",
                "SELECT approval_step_id, approval_status FROM approval_step WHERE approval_status IN ('PENDING','APPROVED','REJECTED','CANCELLED','ESCALATED')", ApprovalStatus.LIFECYCLE, ApprovalStatus.class, "WAITING");
        replay(out, "REIMBURSEMENT", "SELECT entity_id, previous_state, new_state FROM audit_event WHERE entity_type = 'REIMBURSEMENT' AND event_type IN ('REIMBURSEMENT_CREATED','REIMBURSEMENT_PAID') ORDER BY entity_id, occurred_at, event_id",
                "SELECT reimbursement_id, reimbursement_status FROM reimbursement", ReimbursementStatus.LIFECYCLE, ReimbursementStatus.class, null);
        return out;
    }

    private <S extends Enum<S>> void replay(List<ValidationReport.Finding> out, String entityType, String eventsSql, String currentSql,
                                            StateMachine<S> machine, Class<S> type, String initial) {
        Map<String, String> current = new HashMap<>();
        jdbc.query(currentSql, rs -> {
            current.put(rs.getString(1), rs.getString(2));
        });
        Map<String, String> last = new HashMap<>();
        jdbc.query(eventsSql, rs -> {
            String id = rs.getString(1);
            String from = rs.getString(2);
            String to = rs.getString(3);
            if (to == null) {
                return;
            }
            String expectedFrom = last.get(id);
            if (expectedFrom == null && from != null && initial != null && !from.equals(initial)) {
                out.add(new ValidationReport.Finding(entityType, id, "first recorded transition starts at " + from + " instead of " + initial));
            } else if (expectedFrom != null && from != null && !from.equals(expectedFrom)) {
                out.add(new ValidationReport.Finding(entityType, id, "transition " + from + " -> " + to + " does not continue from " + expectedFrom));
            }
            if (from != null && !from.equals(to)) {
                try {
                    if (!machine.canTransition(Enum.valueOf(type, from), Enum.valueOf(type, to))) {
                        out.add(new ValidationReport.Finding(entityType, id, "invalid lifecycle transition " + from + " -> " + to));
                    }
                } catch (IllegalArgumentException e) {
                    out.add(new ValidationReport.Finding(entityType, id, "unknown state in transition " + from + " -> " + to));
                }
            }
            last.put(id, to);
        });
        last.forEach((id, state) -> {
            String now = current.get(id);
            if (now != null && !now.equals(state)) {
                out.add(new ValidationReport.Finding(entityType, id, "audit trail ends in " + state + " but the current state is " + now));
            }
        });
    }

    // ------------------------------------------------------------------ data quality

    private List<ValidationReport.Finding> hierarchy() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EMPLOYEE", "SELECT 'ROOTS', count(*) || ' employees without a manager (exactly one CEO expected)' FROM employee WHERE manager_id IS NULL HAVING count(*) <> 1"));
        out.addAll(sql("EMPLOYEE", "SELECT e.employee_id, 'manager ' || e.manager_id || ' does not rank above the employee' FROM employee e JOIN employee m ON m.employee_id = e.manager_id WHERE m.job_level >= e.job_level"));
        out.addAll(sql("EMPLOYEE", "SELECT employee_id, 'employee is their own manager' FROM employee WHERE manager_id = employee_id"));
        out.addAll(sql("EMPLOYEE", """
                WITH RECURSIVE up(emp, cur, depth) AS (
                    SELECT employee_id, manager_id, 1 FROM employee WHERE manager_id IS NOT NULL
                    UNION ALL SELECT u.emp, e.manager_id, u.depth + 1 FROM up u JOIN employee e ON e.employee_id = u.cur WHERE e.manager_id IS NOT NULL AND u.depth < 30)
                SELECT DISTINCT emp, 'hierarchy cycle or implausible depth' FROM up WHERE depth >= 30 OR cur = emp
                """));
        return out;
    }

    private List<ValidationReport.Finding> negativeAmounts() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EXPENSE_LINE", "SELECT expense_line_id, 'non-positive amount ' || amount || ' without INVALID_AMOUNT explanation' FROM expense_line WHERE amount <= 0 AND NOT (line_status = 'INVALID' AND validation_reason LIKE '%INVALID_AMOUNT%')"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'negative claim total' FROM expense_claim WHERE total_amount < 0"));
        out.addAll(sql("REIMBURSEMENT", "SELECT reimbursement_id, 'non-positive reimbursement' FROM reimbursement WHERE approved_amount <= 0"));
        out.addAll(sql("LEAVE_BALANCE", "SELECT leave_balance_id, 'negative balance component or available balance' FROM leave_balance WHERE opening_balance < 0 OR accrued_balance < 0 OR carried_forward_balance < 0 OR used_balance < 0 OR pending_balance < 0 OR available_balance < 0"));
        out.addAll(sql("BUDGET", "SELECT budget_id, 'negative allocation' FROM budget WHERE allocated_amount < 0"));
        return out;
    }

    private List<ValidationReport.Finding> employmentDates() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EMPLOYEE", "SELECT employee_id, 'termination before joining' FROM employee WHERE termination_date < joining_date"));
        out.addAll(sql("EMPLOYEE", "SELECT employee_id, 'status ' || employment_status || ' inconsistent with termination date' FROM employee WHERE (employment_status = 'TERMINATED') <> (termination_date IS NOT NULL)"));
        return out;
    }

    private List<ValidationReport.Finding> currencies() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EXPENSE_LINE", "SELECT l.expense_line_id, 'line currency ' || l.currency || ' <> claim currency ' || c.currency FROM expense_line l JOIN expense_claim c ON c.expense_claim_id = l.expense_claim_id WHERE l.currency <> c.currency AND COALESCE(c.decision_reason, '') NOT LIKE '%INVALID_CURRENCY%'"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT c.expense_claim_id, 'claim currency ' || c.currency || ' is not the employee''s country currency or unsupported' FROM expense_claim c JOIN employee e ON e.employee_id = c.employee_id JOIN country k ON k.country_code = e.country LEFT JOIN currency u ON u.currency_code = c.currency AND u.supported WHERE (c.currency <> k.currency_code OR u.currency_code IS NULL) AND COALESCE(c.decision_reason, '') NOT LIKE '%INVALID_CURRENCY%'"));
        out.addAll(sql("REIMBURSEMENT", "SELECT r.reimbursement_id, 'reimbursement currency differs from claim' FROM reimbursement r JOIN expense_claim c ON c.expense_claim_id = r.expense_claim_id WHERE r.currency <> c.currency"));
        out.addAll(sql("EXPENSE_POLICY", "SELECT p.expense_policy_id, 'policy currency differs from the country currency' FROM expense_policy p JOIN country k ON k.country_code = p.country WHERE p.currency <> k.currency_code"));
        out.addAll(sql("BUDGET", "SELECT b.budget_id, 'budget currency differs from the cost-center country currency' FROM budget b JOIN cost_center cc ON cc.cost_center_id = b.cost_center_id JOIN country k ON k.country_code = cc.country WHERE b.currency <> k.currency_code"));
        return out;
    }

    private List<ValidationReport.Finding> unexpectedDuplicateReceipts() {
        return sql("EXPENSE_CLAIM", """
                WITH uses AS (
                    SELECT r.receipt_hash, c.expense_claim_id
                    FROM expense_line l JOIN receipt r ON r.receipt_id = l.receipt_id JOIN expense_claim c ON c.expense_claim_id = l.expense_claim_id
                    WHERE c.claim_status NOT IN ('REJECTED', 'CANCELLED')),
                ranked AS (SELECT receipt_hash, expense_claim_id, dense_rank() OVER (PARTITION BY receipt_hash ORDER BY expense_claim_id) AS rnk FROM (SELECT DISTINCT * FROM uses) d)
                SELECT expense_claim_id, 'receipt hash ' || substr(receipt_hash, 1, 12) || '... reused without a DUPLICATE_RECEIPT anomaly'
                FROM ranked k WHERE rnk > 1 AND NOT EXISTS (SELECT 1 FROM detected_anomaly a WHERE a.entity_id = k.expense_claim_id AND a.anomaly_type = 'DUPLICATE_RECEIPT')
                """);
    }

    private List<ValidationReport.Finding> overlappingApprovedLeave() {
        return sql("LEAVE_REQUEST", """
                SELECT a.leave_request_id, 'overlaps approved request ' || b.leave_request_id
                FROM leave_request a JOIN leave_request b ON a.employee_id = b.employee_id AND a.leave_request_id < b.leave_request_id
                WHERE a.request_status = 'APPROVED' AND b.request_status = 'APPROVED' AND a.start_date <= b.end_date AND a.end_date >= b.start_date
                """);
    }

    private List<ValidationReport.Finding> canonicalReferences() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'claim without a cost center but the submitted reference was not preserved' FROM expense_claim WHERE cost_center_id IS NULL AND submitted_cost_center_ref IS NULL"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'claim without a cost center is in the live state ' || claim_status FROM expense_claim WHERE cost_center_id IS NULL AND claim_status IN ('PENDING_APPROVAL', 'APPROVED')"));
        out.addAll(sql("EXPENSE_LINE", "SELECT expense_line_id, 'line without a receipt but with no preserved submitted reference while a receipt was submitted' FROM expense_line WHERE receipt_id IS NULL AND submitted_receipt_ref IS NOT NULL AND validation_reason IS NULL"));
        return out;
    }

    private List<ValidationReport.Finding> preservedInvalidSubmissions() {
        List<ValidationReport.Finding> out = new ArrayList<>();
        out.addAll(sql("SUBMISSION", "SELECT submission_id, intake_status || ': ' || COALESCE(validation_errors::text, '') FROM submission_record WHERE intake_status <> 'ACCEPTED'"));
        out.addAll(sql("EXPENSE_CLAIM", "SELECT expense_claim_id, 'cost center preserved as submitted value ''' || COALESCE(submitted_cost_center_ref, '') || ''', canonical reference is NULL' FROM expense_claim WHERE cost_center_id IS NULL"));
        out.addAll(sql("LEAVE_REQUEST", "SELECT leave_request_id, 'inverted date range preserved as a rejected request' FROM leave_request WHERE start_date > end_date"));
        return out;
    }
}
