package me.niteshh.nexoraflow.support;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.generator.GenerationOrchestrator;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.SizeProfile;

/**
 * Generates ONE complete SMALL dataset (committed) before the first test; tests then mutate it inside rolled-back
 * transactions to prove that validation / evaluation notice corruption.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractGeneratedDatasetTest extends AbstractIntegrationTest {

    protected static final GeneratorSettings SETTINGS = new GeneratorSettings(20261022L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));

    @Autowired
    protected GenerationOrchestrator orchestrator;

    @Override
    protected boolean resetBeforeEachTest() {
        return false;
    }

    @BeforeAll
    void generateDataset() {
        AppClock.clearSimulatedTime();
        orchestrator.generateAll(SETTINGS);
    }
}
