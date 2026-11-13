package me.niteshh.nexoraflow.generator;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.configuration.NexoraProperties;

/**
 * Command-line mode: {@code --nexoraflow.cli.command=full-pipeline} runs the command, prints a JSON summary and exits
 * (exit code 1 on failure). Without the property the application simply starts the REST API.
 *
 * <p>Examples: generate-all, generate-master, generate-leave, generate-expense, generate-anomalies, validate,
 * evaluate, export, reproducibility-check, full-pipeline.
 */
@Component
public class NexoraCommandRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NexoraCommandRunner.class);

    private final NexoraProperties properties;
    private final PipelineService pipeline;
    private final GenerationOrchestrator orchestrator;
    private final JsonSupport json;
    private final ConfigurableApplicationContext context;

    public NexoraCommandRunner(NexoraProperties properties, PipelineService pipeline, GenerationOrchestrator orchestrator,
                               JsonSupport json, ConfigurableApplicationContext context) {
        this.properties = properties;
        this.pipeline = pipeline;
        this.orchestrator = orchestrator;
        this.json = json;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = properties.cli().command();
        if (command == null || command.isBlank()) {
            return;
        }
        int exit = 0;
        try {
            PipelineService.Command parsed = PipelineService.Command.parse(command);
            GeneratorSettings settings = orchestrator.defaultSettings();
            log.info("Running command {} (seed={}, size={}, referenceDate={})", parsed, settings.seed(), settings.profile(), settings.referenceDate());
            Map<String, Object> result = pipeline.execute(parsed, settings, stage -> log.info("stage: {}", stage));
            System.out.println(json.toPrettyJson(result));
        } catch (IllegalArgumentException e) {
            log.error("Unknown command '{}'. Supported: {}", command, PipelineService.commandNames());
            exit = 2;
        } catch (Exception e) {
            log.error("Command {} failed", command, e);
            exit = 1;
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
