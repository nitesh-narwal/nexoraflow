package me.niteshh.nexoraflow.reporting;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reporting", description = "Operational metrics calculated from source records")
public class ReportingController {

    private final ReportingService reporting;

    public ReportingController(ReportingService reporting) {
        this.reporting = reporting;
    }

    @GetMapping("/leave")
    @Operation(summary = "Leave metrics: volumes, balances, utilization by department, approval time")
    public Map<String, Object> leave() {
        return reporting.leaveMetrics();
    }

    @GetMapping("/expenses")
    @Operation(summary = "Expense metrics: claimed / approved / rejected amounts, violations, budget utilization, category distribution")
    public Map<String, Object> expenses() {
        return reporting.expenseMetrics();
    }

    @GetMapping("/approvals")
    @Operation(summary = "Approval metrics: steps, pending, rejected, average time, multi-level frequency, unauthorized attempts")
    public Map<String, Object> approvals() {
        return reporting.approvalMetrics();
    }

    @GetMapping("/financial")
    @Operation(summary = "Financial metrics: reimbursement amounts, duplicate attempts, budget consumption")
    public Map<String, Object> financial() {
        return reporting.financialMetrics();
    }

    @GetMapping("/exceptions")
    @Operation(summary = "Exception report: open exceptions, decisions by reason code, detected anomalies")
    public Map<String, Object> exceptions() {
        return reporting.exceptionMetrics();
    }

    @GetMapping("/summary")
    @Operation(summary = "All operational metrics in one document")
    public Map<String, Object> summary() {
        return reporting.summary();
    }
}
