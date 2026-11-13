package me.niteshh.nexoraflow.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.configuration.NexoraProperties;

/**
 * Runs pipeline commands in the background for the REST API and exposes their status. Only one run is allowed
 * at a time (generation resets and rebuilds the database).
 */
@Service
public class GenerationService {

    public record RunStatus(String runId, String command, String state, String currentStage, Instant startedAt, Instant finishedAt,
                            Map<String, Object> result, String error) {
    }

    private final PipelineService pipeline;
    private final GenerationOrchestrator orchestrator;
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final NexoraProperties properties;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nexoraflow-generator");
        t.setDaemon(true);
        return t;
    });

    private volatile RunStatus status = new RunStatus(null, null, "IDLE", null, null, null, null, null);

    public GenerationService(PipelineService pipeline, GenerationOrchestrator orchestrator, JdbcTemplate jdbc, JsonSupport json,
                             NexoraProperties properties) {
        this.pipeline = pipeline;
        this.orchestrator = orchestrator;
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
    }

    public RunStatus status() {
        return status;
    }

    public GeneratorSettings settings(String size, Long seed, LocalDate referenceDate) {
        GeneratorSettings d = orchestrator.defaultSettings();
        return new GeneratorSettings(seed != null ? seed : d.seed(), size != null ? SizeProfile.parse(size) : d.profile(),
                referenceDate != null ? referenceDate : d.referenceDate());
    }

    public synchronized RunStatus start(PipelineService.Command command, GeneratorSettings settings, boolean async) {
        if ("RUNNING".equals(status.state())) {
            throw NexoraException.conflict(null, "A generation run is already in progress");
        }
        if (!properties.generator().allowReset() && command != PipelineService.Command.VALIDATE
                && command != PipelineService.Command.EVALUATE && command != PipelineService.Command.EXPORT) {
            throw NexoraException.forbidden(null, "Dataset generation is disabled (nexoraflow.generator.allow-reset=false)");
        }
        String runId = "RUN-" + UUID.randomUUID().toString().substring(0, 8);
        status = new RunStatus(runId, command.name(), "RUNNING", "starting", Instant.now(), null, null, null);
        Runnable job = () -> execute(runId, command, settings);
        if (async) {
            executor.submit(job);
        } else {
            job.run();
        }
        return status;
    }

    private void execute(String runId, PipelineService.Command command, GeneratorSettings settings) {
        Instant started = status.startedAt();
        try {
            Map<String, Object> result = pipeline.execute(command, settings, stage -> status =
                    new RunStatus(runId, command.name(), "RUNNING", stage, started, null, null, null));
            status = new RunStatus(runId, command.name(), "COMPLETED", "done", started, Instant.now(), result, null);
            record(runId, command, settings, started, "COMPLETED", result, null);
        } catch (Exception e) {
            status = new RunStatus(runId, command.name(), "FAILED", status.currentStage(), started, Instant.now(), null, e.getMessage());
            record(runId, command, settings, started, "FAILED", null, e.getMessage());
        }
    }

    /** Wall-clock operational log of generator executions (excluded from all logical checksums). */
    private void record(String runId, PipelineService.Command command, GeneratorSettings settings, Instant started, String state,
                        Map<String, Object> result, String error) {
        try {
            jdbc.update("""
                    INSERT INTO generation_run (run_id, command, seed, size_profile, config, application_version, rules_version, started_at,
                        finished_at, status, current_stage, row_counts, error_message)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, runId, command.name(), settings.seed(), settings.profile().name(),
                    json.toJson(Map.of("referenceDate", settings.referenceDate().toString())), "1.0.0", properties.businessRulesVersion(),
                    java.sql.Timestamp.from(started), java.sql.Timestamp.from(Instant.now()), state, "done",
                    result == null ? null : json.toJson(result), error == null ? null : error.substring(0, Math.min(error.length(), 900)));
        } catch (Exception ignored) {
            // the run log is best-effort; it must never fail a generation
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
