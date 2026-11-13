package me.niteshh.nexoraflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.evaluation.EvaluationReport;
import me.niteshh.nexoraflow.evaluation.EvaluationService;
import me.niteshh.nexoraflow.generator.GenerationOrchestrator;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.SizeProfile;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;
import me.niteshh.nexoraflow.validation.ValidationService;

/**
 * Opt-in scale check, not part of the default build because the larger profiles take minutes and several hundred
 * megabytes of database space:
 *
 * <pre>./mvnw test -Dtest=ScaleProfileIntegrationTest -Dnexora.scale=LARGE</pre>
 *
 * It generates the named profile with the default seed, then requires that the independent evaluation finds no mismatch
 * against the ground truth and that every validation check passes. The LARGE profile (five times the anomaly volume) once
 * exposed defects that SMALL and MEDIUM had not; this test is how such a regression is caught.
 */
@EnabledIfSystemProperty(named = "nexora.scale", matches = "SMALL|MEDIUM|LARGE")
class ScaleProfileIntegrationTest extends AbstractIntegrationTest {

    @Autowired GenerationOrchestrator orchestrator;
    @Autowired EvaluationService evaluation;
    @Autowired ValidationService validation;

    @BeforeEach
    void clean() {
        AppClock.clearSimulatedTime();
        databaseReset.resetAll();
    }

    @Test
    void theProfileMatchesTheGroundTruthAndPassesEveryValidationCheck() {
        SizeProfile profile = SizeProfile.parse(System.getProperty("nexora.scale"));
        orchestrator.generateAll(new GeneratorSettings(20261022L, profile, LocalDate.of(2026, 10, 22)));

        EvaluationReport report = evaluation.evaluate();
        assertThat(report.mismatches()).as("mismatches against the ground truth (%s)", profile).isEmpty();
        assertThat(report.anomalyDetection().undetected()).isZero();
        assertThat(report.anomalyDetection().falsePositives()).isZero();

        var validationReport = validation.run();
        assertThat(validationReport.summary().failed()).as("failed validation checks (%s)", profile).isZero();
    }
}
