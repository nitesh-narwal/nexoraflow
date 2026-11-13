package me.niteshh.nexoraflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.export.ExportService;
import me.niteshh.nexoraflow.export.LogicalChecksumService;
import me.niteshh.nexoraflow.export.TableCatalog;
import me.niteshh.nexoraflow.export.TableReader;
import me.niteshh.nexoraflow.generator.ReproducibilityService;
import me.niteshh.nexoraflow.generator.PipelineService;
import me.niteshh.nexoraflow.support.AbstractGeneratedDatasetTest;

class ExportAndReproducibilityIntegrationTest extends AbstractGeneratedDatasetTest {

    @Autowired ExportService export;
    @Autowired LogicalChecksumService checksums;
    @Autowired ReproducibilityService reproducibility;
    @Autowired PipelineService pipeline;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonSupport json;

    @Test
    void exportProducesCsvJsonSqlGroundTruthAndManifestsThatPreserveRelationships() throws Exception {
        ExportService.ExportResult result = export.exportAll(SETTINGS);
        Path base = result.directory();
        for (TableCatalog.ExportTable t : TableCatalog.TABLES) {
            Path csv = base.resolve("generated/" + t.fileName() + ".csv");
            Path jsonFile = base.resolve("generated/" + t.fileName() + ".json");
            assertThat(csv).exists();
            assertThat(jsonFile).exists();
            long dbRows = jdbc.queryForObject("SELECT count(*) FROM " + t.table(), Long.class);
            assertThat(Files.readAllLines(csv)).as(t.fileName() + ".csv").hasSizeGreaterThanOrEqualTo(1);
            assertThat(result.rowCounts().get(t.table())).isEqualTo(dbRows);
            assertThat(json.mapper().readTree(Files.readString(jsonFile)).size()).as(t.fileName() + ".json").isEqualTo((int) dbRows);
        }
        // relationships are expressed through business IDs
        List<String> header = List.of(Files.readAllLines(base.resolve("generated/expense_lines.csv")).get(0).split(","));
        assertThat(header).contains("expense_line_id", "expense_claim_id", "expense_policy_id", "receipt_id", "cost_center_id");
        assertThat(Files.readAllLines(base.resolve("generated/employees.csv")).get(1)).startsWith("EMP-000001,");
        assertThat(base.resolve("ground_truth/ground_truth.csv")).exists();
        assertThat(base.resolve("ground_truth/ground_truth.json")).exists();
        assertThat(base.resolve("generated/sql/nexoraflow_seed.sql")).exists();
        Map<String, Object> manifest = json.toMap(Files.readString(base.resolve("manifests/generation_manifest.json")));
        assertThat(manifest).containsKeys("dataset", "generation", "rowCounts", "logicalChecksums", "overallLogicalChecksum", "operationalMetadata");
        @SuppressWarnings("unchecked")
        Map<String, Object> generation = (Map<String, Object>) manifest.get("generation");
        assertThat(generation.get("seed")).isEqualTo(20261022);
        assertThat(generation.get("businessRulesVersion")).isEqualTo("BR-1.0.0");
        assertThat(manifest.get("overallLogicalChecksum")).isEqualTo(result.overallChecksum());
        assertThat(base.resolve("manifests/checksums.json")).exists();
    }

    @Test
    void theSqlSeedReproducesTheDatasetExactlyInAnEmptyDatabase() throws Exception {
        ExportService.ExportResult result = export.exportAll(SETTINGS);
        String url = POSTGRES.getJdbcUrl().replace(POSTGRES.getDatabaseName(), "seedcheck");
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            admin.createStatement().execute("DROP DATABASE IF EXISTS seedcheck");
            admin.createStatement().execute("CREATE DATABASE seedcheck");
        }
        Flyway.configure().dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        try (Connection conn = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword())) {
            ScriptUtils.executeSqlScript(conn, new FileSystemResource(result.directory().resolve("generated/sql/nexoraflow_seed.sql")));
            JdbcTemplate seeded = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            LogicalChecksumService seededChecksums = new LogicalChecksumService(new TableReader(seeded));
            var original = checksums.compute();
            var reloaded = seededChecksums.compute();
            original.forEach((table, c) -> {
                assertThat(reloaded.get(table).rows()).as(table + " rows").isEqualTo(c.rows());
                assertThat(reloaded.get(table).sha256()).as(table + " checksum").isEqualTo(c.sha256());
            });
            assertThat(seededChecksums.overall(reloaded)).isEqualTo(checksums.overall(original));
        }
    }

    @Test
    void regeneratingTwiceWithTheSameConfigurationYieldsIdenticalRowCountsAndChecksums() {
        Map<String, Object> result = reproducibility.verify(SETTINGS);
        assertThat(result.get("identical")).isEqualTo(true);
        assertThat((List<?>) result.get("differences")).isEmpty();
        assertThat(result.get("overallChecksumRun1")).isEqualTo(result.get("overallChecksumRun2"));
        assertThat(Path.of("target/test-data/manifests/reproducibility_check.json")).exists();
    }

    @Test
    void runningTheStagesSeparatelyEqualsRunningGenerateAll() {
        pipeline.execute(PipelineService.Command.GENERATE_ALL, SETTINGS, s -> { });
        String all = checksums.overall(checksums.compute());
        pipeline.execute(PipelineService.Command.GENERATE_MASTER, SETTINGS, s -> { });
        pipeline.execute(PipelineService.Command.GENERATE_LEAVE, SETTINGS, s -> { });
        pipeline.execute(PipelineService.Command.GENERATE_EXPENSE, SETTINGS, s -> { });
        pipeline.execute(PipelineService.Command.GENERATE_ANOMALIES, SETTINGS, s -> { });
        assertThat(checksums.overall(checksums.compute())).isEqualTo(all);
        // a stage cannot be applied twice on the same dataset
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> pipeline.execute(PipelineService.Command.GENERATE_LEAVE, SETTINGS, s -> { }))
                .hasMessageContaining("already been run");
    }
}
