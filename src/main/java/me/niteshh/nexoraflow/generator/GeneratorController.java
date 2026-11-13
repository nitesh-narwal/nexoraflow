package me.niteshh.nexoraflow.generator;

import java.time.LocalDate;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/v1/generator")
@Tag(name = "Generation and evaluation", description = "Deterministic synthetic data generation (resets and rebuilds the database)")
public class GeneratorController {

    public record RunRequest(
            @NotBlank String command,
            String size,
            Long seed,
            LocalDate referenceDate,
            Boolean async) {
    }

    private final GenerationService generation;

    public GeneratorController(GenerationService generation) {
        this.generation = generation;
    }

    @PostMapping("/run")
    @Operation(summary = "Run a pipeline command in the background",
            description = "Commands: generate-all, generate-master, generate-leave, generate-expense, generate-anomalies, validate, evaluate, "
                    + "export, reproducibility-check, full-pipeline, reset. generate-all / generate-master / full-pipeline reset the database first.")
    public ResponseEntity<GenerationService.RunStatus> run(@Valid @RequestBody RunRequest request) {
        PipelineService.Command command;
        try {
            command = PipelineService.Command.parse(request.command());
        } catch (IllegalArgumentException e) {
            throw me.niteshh.nexoraflow.common.exception.NexoraException.badRequest("Unknown command '" + request.command()
                    + "'. Supported: " + PipelineService.commandNames());
        }
        GenerationService.RunStatus status = generation.start(command,
                generation.settings(request.size(), request.seed(), request.referenceDate()), request.async() == null || request.async());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping("/status")
    @Operation(summary = "Status of the current / last generation run")
    public GenerationService.RunStatus status() {
        return generation.status();
    }
}
