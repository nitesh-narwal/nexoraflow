package me.niteshh.nexoraflow.generator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.configuration.NexoraProperties;
import me.niteshh.nexoraflow.export.LogicalChecksumService;

/**
 * Regenerates the dataset twice with the same configuration and compares row counts and logical checksums
 * table by table. The result is written to {@code manifests/reproducibility_check.json}.
 */
@Service
public class ReproducibilityService {

    private final GenerationOrchestrator orchestrator;
    private final LogicalChecksumService checksums;
    private final JsonSupport json;
    private final NexoraProperties properties;

    public ReproducibilityService(GenerationOrchestrator orchestrator, LogicalChecksumService checksums, JsonSupport json,
                                  NexoraProperties properties) {
        this.orchestrator = orchestrator;
        this.checksums = checksums;
        this.json = json;
        this.properties = properties;
    }

    public Map<String, Object> verify(GeneratorSettings settings) {
        orchestrator.generateAll(settings);
        var first = checksums.compute();
        String firstOverall = checksums.overall(first);
        orchestrator.generateAll(settings);
        var second = checksums.compute();
        String secondOverall = checksums.overall(second);

        List<String> differences = new ArrayList<>();
        first.forEach((table, a) -> {
            var b = second.get(table);
            if (b == null || a.rows() != b.rows()) {
                differences.add(table + ": row count " + a.rows() + " vs " + (b == null ? "missing" : b.rows()));
            } else if (!a.sha256().equals(b.sha256())) {
                differences.add(table + ": checksum differs");
            }
        });
        boolean identical = differences.isEmpty() && firstOverall.equals(secondOverall);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seed", settings.seed());
        result.put("sizeProfile", settings.profile().name());
        result.put("referenceDate", settings.referenceDate().toString());
        result.put("businessRulesVersion", properties.businessRulesVersion());
        result.put("runs", 2);
        result.put("identical", identical);
        result.put("overallChecksumRun1", firstOverall);
        result.put("overallChecksumRun2", secondOverall);
        Map<String, Object> perTable = new LinkedHashMap<>();
        first.forEach((table, a) -> perTable.put(table, Map.of("rows", a.rows(), "sha256Run1", a.sha256(),
                "sha256Run2", second.get(table).sha256(), "match", a.sha256().equals(second.get(table).sha256()))));
        result.put("tables", perTable);
        result.put("differences", differences);
        result.put("checkedAtWallClock", Instant.now().toString());
        try {
            Path dir = Path.of(properties.generator().outputDir(), "manifests");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("reproducibility_check.json"), json.toPrettyJson(result) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
