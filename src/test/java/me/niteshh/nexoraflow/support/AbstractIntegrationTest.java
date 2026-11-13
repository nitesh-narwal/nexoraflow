package me.niteshh.nexoraflow.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.DatabaseResetService;

/**
 * Base class for tests that need a real PostgreSQL. One container is shared by the whole test run
 * (started once, removed by Testcontainers' reaper), and every test starts from an empty database.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("nexoraflow_test").withUsername("test").withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nexoraflow.generator.output-dir", () -> "target/test-data");
    }

    @Autowired
    protected DatabaseResetService databaseReset;
    @Autowired
    protected TestWorld world;
    @Autowired
    protected AppClock clock;

    protected static java.time.LocalDate date(int year, int month, int day) {
        return java.time.LocalDate.of(year, month, day);
    }

    /** Moves the simulated clock (tests control time explicitly). */
    protected static void at(String isoInstant) {
        AppClock.setSimulatedTime(java.time.Instant.parse(isoInstant));
    }

    /** Subclasses that generate one shared dataset per class return false to keep it between tests. */
    protected boolean resetBeforeEachTest() {
        return true;
    }

    @BeforeEach
    void resetDatabase() {
        if (!resetBeforeEachTest()) {
            return;
        }
        AppClock.clearSimulatedTime();
        databaseReset.resetAll();
        world.build();
        // "today" for every test: 2026-10-22 10:00 UTC
        AppClock.setSimulatedTime(TestWorld.NOW);
    }
}
