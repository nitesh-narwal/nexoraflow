package me.niteshh.nexoraflow.acceptance;

import static me.niteshh.nexoraflow.support.ExpenseTestSupport.sha256;
import static me.niteshh.nexoraflow.support.TestWorld.ALICE;
import static me.niteshh.nexoraflow.support.TestWorld.BOB;
import static me.niteshh.nexoraflow.support.TestWorld.ENG_MGR;
import static me.niteshh.nexoraflow.support.TestWorld.FIN_MGR;
import static me.niteshh.nexoraflow.support.TestWorld.LEAD;
import static me.niteshh.nexoraflow.support.TestWorld.LP_ANNUAL_US;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

/**
 * End-to-end acceptance scenarios through the REST API against a real PostgreSQL. Each scenario verifies the
 * HTTP contract, the resulting database state and the audit trail.
 */
@AutoConfigureMockMvc
class ApiAcceptanceTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonSupport json;

    // ------------------------------------------------------------------ helpers

    private MvcResult postJson(String url, Object body, int expectedStatus) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json.toJson(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path).toString();
    }

    private String submitLeave(String employee, String policy, String start, String end, boolean evaluate) throws Exception {
        MvcResult r = postJson("/api/v1/leave-requests" + (evaluate ? "?evaluate=true" : ""),
                Map.of("employeeId", employee, "leavePolicyId", policy, "startDate", start, "endDate", end, "reason", "acceptance"), 201);
        return read(r, "$.leaveRequestId");
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private String one(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private Map<String, Object> mealLine(String merchant, String date, String amount, String receiptSeed) {
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("category", "MEALS");
        line.put("merchantName", merchant);
        line.put("transactionDate", date);
        line.put("description", "business meal");
        line.put("amount", amount);
        if (receiptSeed != null) {
            line.put("receipt", Map.of("fileName", receiptSeed + ".pdf", "fileType", "PDF", "fileSizeBytes", 20000, "receiptHash", sha256(receiptSeed)));
        }
        return line;
    }

    private Map<String, Object> category(String category, String merchant, String date, String amount, String receiptSeed) {
        Map<String, Object> line = mealLine(merchant, date, amount, receiptSeed);
        line.put("category", category);
        return line;
    }

    private String submitClaim(String employee, String costCenter, List<Map<String, Object>> lines, boolean evaluate) throws Exception {
        String start = lines.stream().map(l -> l.get("transactionDate").toString()).min(String::compareTo).orElseThrow();
        String end = lines.stream().map(l -> l.get("transactionDate").toString()).max(String::compareTo).orElseThrow();
        MvcResult r = postJson("/api/v1/expense-claims" + (evaluate ? "?evaluate=true" : ""),
                Map.of("employeeId", employee, "expensePeriodStart", start, "expensePeriodEnd", end, "costCenterId", costCenter,
                        "currency", "USD", "lines", lines), 201);
        return read(r, "$.expenseClaimId");
    }

    private String pendingStepOf(String requestId, String approver) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/approvals/pending").param("approverId", approver)).andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> content = JsonPath.read(r.getResponse().getContentAsString(), "$.content");
        return content.stream().filter(s -> requestId.equals(s.get("requestId"))).map(s -> s.get("approvalStepId").toString()).findFirst().orElseThrow();
    }

    private MvcResult approve(String step, String actor, int expectedStatus) throws Exception {
        return postJson("/api/v1/approvals/" + step + "/approve", Map.of("actorEmployeeId", actor, "comments", "ok", "reason", "approved"), expectedStatus);
    }

    // ------------------------------------------------------------------ 1. valid leave submitted and approved

    @Test
    void scenario01_aValidLeaveRequestIsSubmittedEvaluatedAndApproved() throws Exception {
        String id = submitLeave(ALICE, LP_ANNUAL_US, "2026-11-02", "2026-11-06", true);
        assertThat(id).isEqualTo("LVE-2026-000001");
        mvc.perform(get("/api/v1/leave-requests/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("PENDING_APPROVAL")).andExpect(jsonPath("$.calculatedBusinessDays").value(5));
        mvc.perform(get("/api/v1/employees/" + ALICE + "/leave-balances")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.leavePolicyId == 'LPOL-ANNUAL-US')].pendingBalance").value(5.0));

        approve(pendingStepOf(id, LEAD), LEAD, 200);

        mvc.perform(get("/api/v1/leave-requests/" + id)).andExpect(jsonPath("$.requestStatus").value("APPROVED")).andExpect(jsonPath("$.decision").value("APPROVED"));
        mvc.perform(get("/api/v1/employees/" + ALICE + "/leave-balances"))
                .andExpect(jsonPath("$[?(@.leavePolicyId == 'LPOL-ANNUAL-US')].usedBalance").value(5.0))
                .andExpect(jsonPath("$[?(@.leavePolicyId == 'LPOL-ANNUAL-US')].availableBalance").value(17.0));
        mvc.perform(get("/api/v1/leave-requests/" + id + "/explanation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.evaluation.rulesPassed", hasItem("LV-08"))).andExpect(jsonPath("$.approvalPath", hasSize(1)))
                .andExpect(jsonPath("$.eventHistory[*].eventType", hasItem("STATUS_CHANGED")));
        // database + audit state
        assertThat(jdbc.queryForObject("SELECT used_balance FROM leave_balance WHERE employee_id = ? AND leave_policy_id = ?", java.math.BigDecimal.class, ALICE, LP_ANNUAL_US)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE entity_id = ? AND event_type = 'STATUS_CHANGED'", Integer.class, id)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 2. insufficient balance

    @Test
    void scenario02_anEmployeeWithInsufficientBalanceIsRejectedWithAReason() throws Exception {
        submitLeave(ALICE, LP_ANNUAL_US, "2026-11-02", "2026-11-13", true); // 10 business days reserved of 22
        String second = submitLeave(ALICE, LP_ANNUAL_US, "2026-12-01", "2026-12-18", true); // 14 business days > 12 left
        mvc.perform(get("/api/v1/leave-requests/" + second)).andExpect(jsonPath("$.requestStatus").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason", containsString("INSUFFICIENT_BALANCE")));
        mvc.perform(get("/api/v1/leave-requests/" + second + "/explanation"))
                .andExpect(jsonPath("$.reasonCodes", hasItem("INSUFFICIENT_BALANCE"))).andExpect(jsonPath("$.evaluation.rulesFailed", hasItem("LV-08")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_balance_transaction WHERE leave_request_id = ?", Integer.class, second)).isZero();
    }

    // ------------------------------------------------------------------ 3. overlapping leave

    @Test
    void scenario03_anOverlappingLeaveRequestIsDetectedAndBothRecordsArePreserved() throws Exception {
        String first = submitLeave(ALICE, LP_ANNUAL_US, "2026-11-09", "2026-11-13", true);
        approve(pendingStepOf(first, LEAD), LEAD, 200);
        String second = submitLeave(ALICE, LP_ANNUAL_US, "2026-11-11", "2026-11-18", true);
        mvc.perform(get("/api/v1/leave-requests/" + second)).andExpect(jsonPath("$.requestStatus").value("FLAGGED"))
                .andExpect(jsonPath("$.decisionReason", containsString("OVERLAPPING_APPROVED_LEAVE")));
        mvc.perform(get("/api/v1/leave-requests/" + first)).andExpect(jsonPath("$.requestStatus").value("APPROVED"));
        mvc.perform(get("/api/v1/reports/exceptions")).andExpect(jsonPath("$.detectedAnomaliesByType[*].anomaly_type", hasItem("OVERLAPPING_APPROVED_LEAVE")));
        // database state and audit trail: both requests preserved, nothing reserved for the conflicting one, the conflict recorded
        assertThat(one("SELECT request_status FROM leave_request WHERE leave_request_id = ?", first)).isEqualTo("APPROVED");
        assertThat(one("SELECT request_status FROM leave_request WHERE leave_request_id = ?", second)).isEqualTo("FLAGGED");
        assertThat(count("SELECT count(*) FROM leave_balance_transaction WHERE leave_request_id = ?", second)).isZero();
        assertThat(count("SELECT count(*) FROM detected_anomaly WHERE entity_id = ? AND anomaly_type = 'OVERLAPPING_APPROVED_LEAVE'", second)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE entity_id = ? AND event_type = 'ANOMALY_DETECTED'", second)).isGreaterThanOrEqualTo(1);
        assertThat(one("SELECT new_state FROM audit_event WHERE entity_id = ? AND event_type = 'STATUS_CHANGED' ORDER BY occurred_at DESC, event_id DESC LIMIT 1", second)).isEqualTo("FLAGGED");
    }

    // ------------------------------------------------------------------ 4. valid expense claim completes approval

    @Test
    void scenario04_aValidExpenseClaimCompletesItsMultiLevelApprovalWorkflow() throws Exception {
        String claim = submitClaim(ALICE, "CC-001", List.of(
                category("TRAVEL", "Skyway Air", "2026-10-19", "1800.00", "air-1"),
                category("TRAVEL", "Skyway Air", "2026-10-20", "1800.00", "air-2")), true);
        mvc.perform(get("/api/v1/expense-claims/" + claim)).andExpect(jsonPath("$.claimStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.totalAmount").value(3600.0)).andExpect(jsonPath("$.lines", hasSize(2)));
        mvc.perform(get("/api/v1/expense-claims/" + claim + "/explanation")).andExpect(jsonPath("$.approvalPath", hasSize(2)))
                .andExpect(jsonPath("$.approvalPath[0].approverEmployeeId").value(ENG_MGR)).andExpect(jsonPath("$.approvalPath[1].approverEmployeeId").value(FIN_MGR));
        String level1 = pendingStepOf(claim, ENG_MGR);
        // the finance step is still WAITING: acting out of order is refused with 409 and recorded
        String level2 = level1.replace("-01", "-02");
        approve(level2, FIN_MGR, 409);
        approve(level1, ENG_MGR, 200);
        approve(pendingStepOf(claim, FIN_MGR), FIN_MGR, 200);
        mvc.perform(get("/api/v1/expense-claims/" + claim)).andExpect(jsonPath("$.claimStatus").value("APPROVED")).andExpect(jsonPath("$.eligibilityStatus").value("ELIGIBLE"));
        // database state and audit trail: two approved steps in order, the out-of-order attempt recorded, one reimbursement created
        assertThat(count("SELECT count(*) FROM approval_step WHERE expense_claim_id = ? AND approval_status = 'APPROVED'", claim)).isEqualTo(2);
        assertThat(one("SELECT claim_status || '/' || eligibility_status FROM expense_claim WHERE expense_claim_id = ?", claim)).isEqualTo("APPROVED/ELIGIBLE");
        assertThat(count("SELECT count(*) FROM reimbursement WHERE expense_claim_id = ?", claim)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'APPROVAL_STEP_APPROVED' AND metadata::text LIKE ?", "%" + claim + "%")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'INVALID_TRANSITION_REJECTED' AND reason = 'INVALID_STEP_ORDER'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'REIMBURSEMENT_CREATED' AND metadata::text LIKE ?", "%" + claim + "%")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 5. missing receipt

    @Test
    void scenario05_anExpenseWithAMissingReceiptIsFlagged() throws Exception {
        String claim = submitClaim(ALICE, "CC-001", List.of(mealLine("Cafe Roma", "2026-10-20", "40.00", null)), true);
        mvc.perform(get("/api/v1/expense-claims/" + claim)).andExpect(jsonPath("$.claimStatus").value("FLAGGED"))
                .andExpect(jsonPath("$.decisionReason", containsString("RECEIPT_MISSING"))).andExpect(jsonPath("$.lines[0].lineStatus").value("FLAGGED"));
        mvc.perform(get("/api/v1/approvals/pending").param("approverId", LEAD)).andExpect(jsonPath("$.content", hasSize(0)));
        // database state and audit trail: held for review, no approval route, no reimbursement, the failed rule is in the decision trace
        assertThat(one("SELECT claim_status FROM expense_claim WHERE expense_claim_id = ?", claim)).isEqualTo("FLAGGED");
        assertThat(count("SELECT count(*) FROM approval_step WHERE expense_claim_id = ?", claim)).isZero();
        assertThat(count("SELECT count(*) FROM reimbursement WHERE expense_claim_id = ?", claim)).isZero();
        assertThat(count("SELECT count(*) FROM decision_trace WHERE request_id = ? AND reason_codes LIKE '%RECEIPT_MISSING%'", claim)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_event WHERE entity_id = ? AND event_type = 'REQUEST_EVALUATED'", claim)).isEqualTo(1);
        assertThat(one("SELECT new_state FROM audit_event WHERE entity_id = ? AND event_type = 'STATUS_CHANGED' ORDER BY occurred_at DESC, event_id DESC LIMIT 1", claim)).isEqualTo("FLAGGED");
    }

    // ------------------------------------------------------------------ 6. policy limit exceeded

    @Test
    void scenario06_aClaimThatExceedsAPolicyLimitIsRejected() throws Exception {
        String claim = submitClaim(ALICE, "CC-001", List.of(mealLine("Steakhouse", "2026-10-20", "90.00", "limit-1")), true);
        mvc.perform(get("/api/v1/expense-claims/" + claim)).andExpect(jsonPath("$.claimStatus").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason", containsString("POLICY_LIMIT_EXCEEDED")));
        mvc.perform(get("/api/v1/expense-claims/" + claim + "/explanation")).andExpect(jsonPath("$.evaluation.rulesFailed", hasItem("EX-09")));
        // database state and audit trail: rejected line by line, no route, no reimbursement, the decision is traceable
        assertThat(one("SELECT claim_status FROM expense_claim WHERE expense_claim_id = ?", claim)).isEqualTo("REJECTED");
        assertThat(one("SELECT line_status || '/' || validation_reason FROM expense_line WHERE expense_claim_id = ?", claim)).isEqualTo("INVALID/POLICY_LIMIT_EXCEEDED");
        assertThat(count("SELECT count(*) FROM approval_step WHERE expense_claim_id = ?", claim)).isZero();
        assertThat(count("SELECT count(*) FROM reimbursement WHERE expense_claim_id = ?", claim)).isZero();
        assertThat(count("SELECT count(*) FROM decision_trace WHERE request_id = ? AND decision = 'REJECTED'", claim)).isEqualTo(1);
        assertThat(one("SELECT new_state FROM audit_event WHERE entity_id = ? AND event_type = 'STATUS_CHANGED' ORDER BY occurred_at DESC, event_id DESC LIMIT 1", claim)).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------ 7. split claim

    @Test
    void scenario07_aSuspiciousSplitClaimIsIdentifiedWhileUnrelatedClaimsAreNot() throws Exception {
        String first = submitClaim(ALICE, "CC-001", List.of(category("CLIENT_ENTERTAINMENT", "Bistro Nova", "2026-10-19", "450.00", "split-1")), true);
        String unrelated = submitClaim(ALICE, "CC-001", List.of(category("CLIENT_ENTERTAINMENT", "Harbour Grill", "2026-10-18", "400.00", "split-u")), true);
        String second = submitClaim(ALICE, "CC-001", List.of(category("CLIENT_ENTERTAINMENT", "Bistro Nova", "2026-10-20", "400.00", "split-2")), true);
        mvc.perform(get("/api/v1/expense-claims/" + second)).andExpect(jsonPath("$.claimStatus").value("FLAGGED"))
                .andExpect(jsonPath("$.decisionReason", containsString("SUSPICIOUS_SPLIT_CLAIM")));
        mvc.perform(get("/api/v1/expense-claims/" + first)).andExpect(jsonPath("$.claimStatus").value("FLAGGED"));
        mvc.perform(get("/api/v1/expense-claims/" + unrelated)).andExpect(jsonPath("$.claimStatus").value("PENDING_APPROVAL"));
        mvc.perform(get("/api/v1/expense-claims/" + second + "/explanation"))
                .andExpect(jsonPath("$.evaluation.rulesEvaluated[?(@.ruleId == 'EX-18')].details.combinedAmountUsd").value(850.0));
        // database state and audit trail: both related claims held (the first one retroactively, its open step cancelled), the pattern recorded
        assertThat(jdbc.queryForList("SELECT expense_claim_id FROM expense_claim WHERE claim_status = 'FLAGGED' ORDER BY 1", String.class)).containsExactly(first, second);
        assertThat(count("SELECT count(*) FROM approval_step WHERE expense_claim_id = ? AND approval_status = 'CANCELLED'", first)).isEqualTo(2); // both levels of its route
        assertThat(count("SELECT count(*) FROM approval_step WHERE expense_claim_id = ? AND approval_status = 'PENDING'", unrelated)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM detected_anomaly WHERE anomaly_type = 'SUSPICIOUS_SPLIT_CLAIM' AND entity_id IN (?, ?)", first, second)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM detected_anomaly WHERE entity_id = ?", unrelated)).isZero();
        assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'ANOMALY_DETECTED' AND entity_id IN (?, ?)", first, second)).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ 8. self approval

    @Test
    void scenario08_anUnauthorizedSelfApprovalIsPreventedAndAudited() throws Exception {
        String claim = submitClaim(ALICE, "CC-001", List.of(mealLine("Cafe Roma", "2026-10-20", "40.00", "self-1")), true);
        String step = pendingStepOf(claim, LEAD);
        MvcResult denied = approve(step, ALICE, 403);
        assertThat(read(denied, "$.reasonCode")).isEqualTo("SELF_APPROVAL_NOT_ALLOWED");
        assertThat(denied.getResponse().getContentAsString()).doesNotContain("Exception").doesNotContain("at me.niteshh");
        mvc.perform(get("/api/v1/approvals/" + step)).andExpect(jsonPath("$.approvalStatus").value("PENDING"));
        mvc.perform(get("/api/v1/expense-claims/" + claim)).andExpect(jsonPath("$.claimStatus").value("PENDING_APPROVAL"));
        mvc.perform(get("/api/v1/audit-events").param("entityId", claim).param("eventType", "APPROVAL_ATTEMPT_REJECTED"))
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].actor").value(ALICE))
                .andExpect(jsonPath("$.content[0].reason").value("SELF_APPROVAL_NOT_ALLOWED"));
        // somebody who is not the assigned approver cannot approve either
        assertThat(read(approve(step, BOB, 403), "$.reasonCode")).isEqualTo("UNAUTHORIZED_APPROVER");
    }

    // ------------------------------------------------------------------ 9 + 10. reimbursement exactly once, duplicate rejected

    @Test
    void scenario09and10_anApprovedClaimGeneratesExactlyOneReimbursementAndADuplicateAttemptIsRejected() throws Exception {
        String claim = submitClaim(ALICE, "CC-001", List.of(mealLine("Cafe Roma", "2026-10-20", "40.00", "rmb-1")), true);
        mvc.perform(get("/api/v1/reimbursements").param("employeeId", ALICE)).andExpect(jsonPath("$.content", hasSize(0))); // none before approval
        approve(pendingStepOf(claim, LEAD), LEAD, 200);
        MvcResult list = mvc.perform(get("/api/v1/reimbursements").param("employeeId", ALICE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1))).andExpect(jsonPath("$.content[0].approvedAmount").value(40.0))
                .andExpect(jsonPath("$.content[0].reimbursementStatus").value("PENDING_PAYMENT")).andReturn();
        String reimbursementId = read(list, "$.content[0].reimbursementId");
        assertThat(reimbursementId).isEqualTo("RMB-2026-000001");

        // a payroll run asks again: 409 DUPLICATE_REIMBURSEMENT, nothing new is created, the attempt is recorded
        MvcResult dup = postJson("/api/v1/reimbursements", Map.of("expenseClaimId", claim, "requestedBy", "PAYROLL-RUN"), 409);
        assertThat(read(dup, "$.reasonCode")).isEqualTo("DUPLICATE_REIMBURSEMENT");
        mvc.perform(get("/api/v1/reimbursements").param("employeeId", ALICE)).andExpect(jsonPath("$.totalElements").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reimbursement_attempt WHERE expense_claim_id = ? AND outcome = 'DUPLICATE_REJECTED'", Integer.class, claim)).isEqualTo(1);
        mvc.perform(get("/api/v1/audit-events").param("eventType", "REIMBURSEMENT_DUPLICATE_REJECTED")).andExpect(jsonPath("$.content", hasSize(1)));

        // reimbursing a claim that is not approved is refused with 422
        String pending = submitClaim(ALICE, "CC-001", List.of(mealLine("Noodle Bar", "2026-10-21", "30.00", "rmb-2")), true);
        assertThat(read(postJson("/api/v1/reimbursements", Map.of("expenseClaimId", pending, "requestedBy", "PAYROLL-RUN"), 422), "$.reasonCode")).isEqualTo("CLAIM_NOT_REIMBURSABLE");
        // payment lifecycle
        mvc.perform(post("/api/v1/reimbursements/" + reimbursementId + "/process").contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"PAYROLL\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reimbursementStatus").value("PAID")).andExpect(jsonPath("$.paymentReference", containsString("PAY-2026-")));
        mvc.perform(post("/api/v1/reimbursements/" + reimbursementId + "/process").contentType(MediaType.APPLICATION_JSON).content("{\"actor\":\"PAYROLL\"}"))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ API contract

    @Test
    void invalidRequestsGetConsistentErrorsWithoutInternals() throws Exception {
        mvc.perform(post("/api/v1/leave-requests").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field", hasItem("employeeId")))
                .andExpect(jsonPath("$.title").value("Bad Request")).andExpect(content().string(not(containsString("Exception"))));
        mvc.perform(get("/api/v1/leave-requests/LVE-2026-999999")).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail", containsString("was not found")));
        mvc.perform(get("/api/v1/employees").param("status", "NOT_A_STATUS")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/leave-requests").contentType(MediaType.APPLICATION_JSON).content("{not json")).andExpect(status().isBadRequest());
        // a submission with an unknown employee is rejected (422) but preserved in staging
        MvcResult ghost = postJson("/api/v1/leave-requests", Map.of("employeeId", "EMP-424242", "leavePolicyId", LP_ANNUAL_US, "startDate", "2026-11-02", "endDate", "2026-11-03"), 422);
        String submissionId = read(ghost, "$.submissionId");
        assertThat(submissionId).startsWith("SUB-");
        assertThat(jdbc.queryForObject("SELECT intake_status FROM submission_record WHERE submission_id = ?", String.class, submissionId)).isEqualTo("REJECTED_AT_INTAKE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request", Integer.class)).isZero();
    }

    @Test
    void employeesCanBeCreatedListedAndFiltered() throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("firstName", "Nina");
        body.put("lastName", "Nova");
        body.put("email", "nina.nova@nexora-technologies.example");
        body.put("departmentId", "DEP-ENG");
        body.put("designation", "Software Engineer");
        body.put("jobLevel", 5);
        body.put("managerId", LEAD);
        body.put("country", "US");
        body.put("employmentType", "FULL_TIME");
        body.put("joiningDate", "2026-10-01");
        body.put("costCenterId", "CC-001");
        MvcResult created = postJson("/api/v1/employees", body, 201);
        String id = read(created, "$.employeeId");
        assertThat(id).isEqualTo("EMP-000015");
        mvc.perform(get("/api/v1/employees/" + id)).andExpect(jsonPath("$.managerId").value(LEAD));
        // leave policies and balances were provisioned (pro-rated: Oct..Dec = 3 months of 20 days = 5)
        mvc.perform(get("/api/v1/employees/" + id + "/leave-balances")).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.leavePolicyId == 'LPOL-ANNUAL-US')].availableBalance").value(5.0));
        postJson("/api/v1/employees", body, 409); // duplicate e-mail
        mvc.perform(get("/api/v1/employees").param("departmentId", "DEP-ENG").param("size", "5").param("page", "1"))
                .andExpect(jsonPath("$.size").value(5)).andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(10)));
        mvc.perform(get("/api/v1/employees").param("managerId", LEAD)).andExpect(jsonPath("$.content[*].employeeId", hasItem(ALICE)));
    }

    @Test
    void configurationEndpointsExposePoliciesRulesAndCostCenters() throws Exception {
        mvc.perform(get("/api/v1/leave-policies")).andExpect(jsonPath("$.content", hasSize(2)));
        mvc.perform(get("/api/v1/expense-policies").param("country", "US").param("category", "MEALS")).andExpect(jsonPath("$.content[0].maximumPerTransaction").value(75.0));
        mvc.perform(get("/api/v1/approval-rules").param("workflowType", "EXPENSE").param("status", "ACTIVE")).andExpect(jsonPath("$.totalElements").value(7));
        mvc.perform(get("/api/v1/approval-rules").param("status", "INACTIVE")).andExpect(jsonPath("$.content[0].approvalRuleId").value("AR-EX-009"));
        mvc.perform(get("/api/v1/cost-centers")).andExpect(jsonPath("$.content", hasSize(4)));
        mvc.perform(get("/api/v1/cost-centers/CC-004/budget").param("date", "2026-10-22")).andExpect(jsonPath("$.allocated").value(3000.0)).andExpect(jsonPath("$.remaining").value(3000.0));
    }

    @Test
    void theOpenApiDocumentAndSwaggerUiAreServed() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/leave-requests/{id}/evaluate']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/approvals/{id}/approve']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/reimbursements']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/generator/run']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/validation/report']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/evaluation/report']").exists());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void operationalReportsAreCalculatedFromSourceRecords() throws Exception {
        String claim = submitClaim(ALICE, "CC-001", List.of(mealLine("Cafe Roma", "2026-10-20", "40.00", "rep-1")), true);
        approve(pendingStepOf(claim, LEAD), LEAD, 200);
        submitClaim(ALICE, "CC-001", List.of(mealLine("Noodle Bar", "2026-10-21", "30.00", null)), true); // missing receipt
        mvc.perform(get("/api/v1/reports/expenses")).andExpect(jsonPath("$.totals.total_claims").value(2)).andExpect(jsonPath("$.totals.approved_usd").value(40.0))
                .andExpect(jsonPath("$.totals.missing_receipts").value(1));
        mvc.perform(get("/api/v1/reports/financial")).andExpect(jsonPath("$.totals.eligible_reimbursement_usd").value(40.0));
        mvc.perform(get("/api/v1/reports/approvals")).andExpect(jsonPath("$.totals.approval_steps").value(1));
        mvc.perform(get("/api/v1/reports/summary")).andExpect(jsonPath("$.leave").exists()).andExpect(jsonPath("$.exceptions.openExceptions").exists());
    }

    @Test
    void claimsInAnUnsupportedCurrencyAreStillCountedByTheReports() throws Exception {
        // rejected as INVALID_CURRENCY: it can be neither converted nor approved, but it is a claim and a policy violation
        MvcResult r = postJson("/api/v1/expense-claims?evaluate=true",
                Map.of("employeeId", ALICE, "expensePeriodStart", "2026-10-20", "expensePeriodEnd", "2026-10-20", "costCenterId", "CC-001",
                        "currency", "ZZZ", "lines", List.of(mealLine("Cafe Roma", "2026-10-20", "40.00", "zzz-1"))), 201);
        String id = read(r, "$.expenseClaimId");
        mvc.perform(get("/api/v1/expense-claims/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.claimStatus").value("REJECTED"))
                .andExpect(jsonPath("$.decisionReason").value(org.hamcrest.Matchers.containsString("INVALID_CURRENCY")));
        mvc.perform(get("/api/v1/reports/expenses")).andExpect(jsonPath("$.totals.total_claims").value(1))
                .andExpect(jsonPath("$.totals.policy_violations").value(1)).andExpect(jsonPath("$.totals.claimed_usd").value(0.0));
    }
}
