package me.niteshh.nexoraflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.evaluation.EvaluationReport;
import me.niteshh.nexoraflow.evaluation.EvaluationService;
import me.niteshh.nexoraflow.generator.GenerationOrchestrator;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.SizeProfile;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

class FullGenerationIntegrationTest extends AbstractIntegrationTest {

    @Autowired GenerationOrchestrator orchestrator;
    @Autowired EvaluationService evaluation;
    @Autowired JsonSupport json;
    @Autowired me.niteshh.nexoraflow.validation.ValidationService validation;
    @Autowired me.niteshh.nexoraflow.export.ExportService export;
    @Autowired me.niteshh.nexoraflow.export.LogicalChecksumService checksums;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        AppClock.clearSimulatedTime();
        databaseReset.resetAll();
    }

    @Test
    void completeSmallDatasetMatchesGroundTruth() {
        GeneratorSettings settings = new GeneratorSettings(20261022L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));
        var stages = orchestrator.generateAll(settings);
        stages.forEach(s -> System.out.println("STAGE " + s));
        EvaluationReport report = evaluation.evaluate();
        System.out.println("ANOMALY " + report.anomalyDetection());
        System.out.println("WORKFLOW " + report.workflowDecisions());
        System.out.println("REIMBURSEMENT " + report.reimbursementDecisions());
        System.out.println("CONTROLS " + report.controls());
        System.out.println("TOTALS unlabelled=" + report.totals().unlabelledRequests() + " valid=" + report.totals().validRecords() + " invalid=" + report.totals().invalidRecords());
        report.scenarios().forEach(s -> System.out.println("SCENARIO " + s));
        report.mismatches().forEach(m -> {
            System.out.println("MISMATCH " + m);
            if (m.entityType().equals("LEAVE_REQUEST")) {
                System.out.println("  DEBUG request: " + jdbc.queryForMap("SELECT * FROM leave_request WHERE leave_request_id = ?", m.entityId()));
                String emp = jdbc.queryForObject("SELECT employee_id FROM leave_request WHERE leave_request_id = ?", String.class, m.entityId());
                System.out.println("  DEBUG balances: " + jdbc.queryForList("SELECT * FROM leave_balance WHERE employee_id = ?", emp));
                System.out.println("  DEBUG ledger: " + jdbc.queryForList("SELECT txn_id, leave_balance_id, leave_request_id, txn_type, opening_delta, accrued_delta, carried_forward_delta, used_delta, pending_delta, occurred_at FROM leave_balance_transaction WHERE leave_balance_id IN (SELECT leave_balance_id FROM leave_balance WHERE employee_id = ?) ORDER BY occurred_at, txn_id", emp));
                System.out.println("  DEBUG trace: " + jdbc.queryForList("SELECT trace::text FROM decision_trace WHERE request_id = ?", m.entityId()));
            }
        });
        assertThat(report.mismatches()).isEmpty();

        var validationReport = validation.run();
        validationReport.checks().forEach(c -> System.out.println("CHECK " + c.id() + " " + c.status() + " findings=" + c.findingCount() + (c.findingCount() > 0 && !c.status().equals("INFO") ? " " + c.findings().subList(0, Math.min(3, c.findings().size())) : "")));
        assertThat(validationReport.summary().failed()).as("failed validation checks").isZero();

        var exported = export.exportAll(settings);
        System.out.println("EXPORT " + exported.rowCounts());
        System.out.println("CHECKSUM " + exported.overallChecksum());
        assertThat(exported.rowCounts().get("employee")).isEqualTo(50L);
    }
}
