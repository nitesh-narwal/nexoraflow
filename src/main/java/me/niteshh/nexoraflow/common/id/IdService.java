package me.niteshh.nexoraflow.common.id;

import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Generates consistent, human-readable business identifiers from atomic database counters.
 *
 * <p>Counters live in {@code id_counter}. Each call is a single {@code INSERT .. ON CONFLICT DO UPDATE}
 * statement, so concurrent callers are serialised by the row lock and never receive the same number.
 * The counter increment participates in the caller's transaction: a rolled-back transaction also rolls
 * back its ID allocations, so there are no gaps.
 *
 * <p>Examples: EMP-000001, LVE-2026-000001, EXP-2026-000001, EXPL-EXP-2026-000001-01,
 * APR-LVE-2026-000001-01, RMB-2026-000001.
 */
@Service
public class IdService {

    private static final String NEXT_SQL = """
            INSERT INTO id_counter (counter_key, next_value) VALUES (?, 2)
            ON CONFLICT (counter_key) DO UPDATE SET next_value = id_counter.next_value + 1
            RETURNING next_value - 1
            """;

    private final JdbcTemplate jdbc;

    public IdService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the next number (starting at 1) of the counter. */
    public long next(String counterKey) {
        Long value = jdbc.queryForObject(NEXT_SQL, Long.class, counterKey);
        return value == null ? 1L : value;
    }

    /** Positions a counter so that the next call returns {@code nextValue}. Used by the generator. */
    public void position(String counterKey, long nextValue) {
        jdbc.update("""
                INSERT INTO id_counter (counter_key, next_value) VALUES (?, ?)
                ON CONFLICT (counter_key) DO UPDATE SET next_value = EXCLUDED.next_value
                """, counterKey, nextValue);
    }

    public static String pad(long value, int width) {
        return String.format("%0" + width + "d", value);
    }

    public String employeeId() {
        return "EMP-" + pad(next("EMP"), 6);
    }

    public String leaveRequestId(LocalDate submissionDate) {
        int year = submissionDate.getYear();
        return "LVE-" + year + "-" + pad(next("LVE-" + year), 6);
    }

    public String expenseClaimId(LocalDate submissionDate) {
        int year = submissionDate.getYear();
        return "EXP-" + year + "-" + pad(next("EXP-" + year), 6);
    }

    public static String expenseLineId(String claimId, int lineNumber) {
        return "EXPL-" + claimId + "-" + pad(lineNumber, 2);
    }

    public static String approvalStepId(String requestId, int stepSequence) {
        return "APR-" + requestId + "-" + pad(stepSequence, 2);
    }

    public String reimbursementId(LocalDate approvalDate) {
        int year = approvalDate.getYear();
        return "RMB-" + year + "-" + pad(next("RMB-" + year), 6);
    }

    public String paymentReference(LocalDate paymentDate) {
        int year = paymentDate.getYear();
        return "PAY-" + year + "-" + pad(next("PAY-" + year), 6);
    }

    public String reimbursementAttemptId() {
        return "RMA-" + pad(next("RMA"), 6);
    }

    public String receiptId() {
        return "RCP-" + pad(next("RCP"), 6);
    }

    public String auditEventId() {
        return "EVT-" + pad(next("EVT"), 8);
    }

    public String decisionTraceId() {
        return "DEC-" + pad(next("DEC"), 7);
    }

    public String anomalyId() {
        return "ANM-" + pad(next("ANM"), 6);
    }

    public String submissionId() {
        return "SUB-" + pad(next("SUB"), 6);
    }

    public String leaveBalanceId() {
        return "LBAL-" + pad(next("LBAL"), 6);
    }

    public String leaveBalanceTxnId() {
        return "LBT-" + pad(next("LBT"), 7);
    }

    public String costCenterAssignmentId() {
        return "ECC-" + pad(next("ECC"), 6);
    }

    public String policyAssignmentId() {
        return "EPA-" + pad(next("EPA"), 6);
    }
}
