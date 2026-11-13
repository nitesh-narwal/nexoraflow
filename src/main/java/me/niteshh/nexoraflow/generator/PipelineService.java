package me.niteshh.nexoraflow.generator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.evaluation.EvaluationReport;
import me.niteshh.nexoraflow.evaluation.EvaluationService;
import me.niteshh.nexoraflow.export.ExportService;
import me.niteshh.nexoraflow.export.ReportWriter;
import me.niteshh.nexoraflow.validation.ValidationReport;
import me.niteshh.nexoraflow.validation.ValidationService;

/** Executes the named pipeline commands (shared by the CLI and the REST API). */
@Service
public class PipelineService {

    public enum Command {
        RESET, GENERATE_ALL, GENERATE_MASTER, GENERATE_LEAVE, GENERATE_EXPENSE, GENERATE_ANOMALIES,
        VALIDATE, EVALUATE, EXPORT, REPRODUCIBILITY_CHECK, FULL_PIPELINE;

        public static Command parse(String value) {
            return Command.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        }
    }

    private final GenerationOrchestrator orchestrator;
    private final ValidationService validation;
    private final EvaluationService evaluation;
    private final ExportService export;
    private final ReportWriter reports;
    private final ReproducibilityService reproducibility;
    private final JdbcTemplate jdbc;

    public PipelineService(GenerationOrchestrator orchestrator, ValidationService validation, EvaluationService evaluation,
                           ExportService export, ReportWriter reports, ReproducibilityService reproducibility, JdbcTemplate jdbc) {
        this.orchestrator = orchestrator;
        this.validation = validation;
        this.evaluation = evaluation;
        this.export = export;
        this.reports = reports;
        this.reproducibility = reproducibility;
        this.jdbc = jdbc;
    }

    public Map<String, Object> execute(Command command, GeneratorSettings settings, Consumer<String> progress) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", command.name());
        result.put("seed", settings.seed());
        result.put("sizeProfile", settings.profile().name());
        result.put("referenceDate", settings.referenceDate().toString());
        long started = System.currentTimeMillis();
        switch (command) {
            case RESET -> {
                progress.accept("reset");
                orchestrator.resetDatabase();
            }
            case GENERATE_MASTER -> {
                progress.accept("master");
                orchestrator.resetDatabase();
                result.put("master", orchestrator.generateMaster(settings));
            }
            case GENERATE_LEAVE -> {
                requireMaster();
                requireEmpty("leave_request", "generate-leave");
                progress.accept("leave");
                result.put("leave", orchestrator.generateLeave(settings));
            }
            case GENERATE_EXPENSE -> {
                requireMaster();
                requireEmpty("expense_claim", "generate-expense");
                progress.accept("expense");
                result.put("expense", orchestrator.generateExpense(settings));
            }
            case GENERATE_ANOMALIES -> {
                requireMaster();
                requireEmpty("ground_truth.label WHERE scenario_id LIKE 'SCN-%'", "generate-anomalies");
                progress.accept("anomalies");
                result.put("anomalies", orchestrator.generateAnomalies(settings));
            }
            case GENERATE_ALL -> {
                progress.accept("generate");
                result.put("stages", orchestrator.generateAll(settings));
            }
            case VALIDATE -> {
                progress.accept("validate");
                result.put("validation", validation.runAndStore().summary());
            }
            case EVALUATE -> {
                progress.accept("evaluate");
                EvaluationReport report = evaluation.evaluateAndStore();
                result.put("evaluation", Map.of("anomalyDetection", report.anomalyDetection(), "workflowDecisions", report.workflowDecisions(),
                        "reimbursementDecisions", report.reimbursementDecisions(), "mismatches", report.mismatches().size()));
            }
            case EXPORT -> {
                progress.accept("export");
                var exported = export.exportAll(settings);
                result.put("export", Map.of("directory", exported.directory().toString(), "files", exported.files(),
                        "overallLogicalChecksum", exported.overallChecksum()));
            }
            case REPRODUCIBILITY_CHECK -> {
                progress.accept("reproducibility");
                result.put("reproducibility", reproducibility.verify(settings));
            }
            case FULL_PIPELINE -> {
                progress.accept("generate");
                result.put("stages", orchestrator.generateAll(settings));
                progress.accept("validate");
                ValidationReport v = validation.runAndStore();
                progress.accept("evaluate");
                EvaluationReport e = evaluation.evaluateAndStore();
                progress.accept("export");
                var exported = export.exportAll(settings);
                progress.accept("reports");
                reports.writeAll(settings, v, e);
                result.put("validation", v.summary());
                result.put("evaluation", Map.of("anomalyDetection", e.anomalyDetection(), "workflowDecisions", e.workflowDecisions(),
                        "reimbursementDecisions", e.reimbursementDecisions(), "mismatches", e.mismatches().size()));
                result.put("export", Map.of("directory", exported.directory().toString(), "files", exported.files(),
                        "overallLogicalChecksum", exported.overallChecksum()));
            }
        }
        result.put("elapsedSeconds", (System.currentTimeMillis() - started) / 1000.0);
        return result;
    }

    private void requireMaster() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM employee", Integer.class);
        if (n == null || n == 0) {
            throw NexoraException.badRequest("No master data exists. Run generate-master (or generate-all) first.");
        }
    }

    private void requireEmpty(String fromClause, String command) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + fromClause, Integer.class);
        if (n != null && n > 0) {
            throw NexoraException.badRequest(command + " has already been run for this dataset. Use generate-all (it resets) or reset first.");
        }
    }

    public static List<String> commandNames() {
        return java.util.Arrays.stream(Command.values()).map(c -> c.name().toLowerCase(Locale.ROOT).replace('_', '-')).toList();
    }
}
