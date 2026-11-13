package me.niteshh.nexoraflow.validation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.niteshh.nexoraflow.common.exception.NexoraException;

@RestController
@RequestMapping("/api/v1/validation")
@Tag(name = "Generation and evaluation")
public class ValidationController {

    private final ValidationService validation;

    public ValidationController(ValidationService validation) {
        this.validation = validation;
    }

    @PostMapping("/run")
    @Operation(summary = "Run all integrity, business and data-quality checks and store the report")
    public ValidationReport run() {
        return validation.runAndStore();
    }

    @GetMapping("/report")
    @Operation(summary = "Latest structured validation report (which check failed, for which records)")
    public ValidationReport report() {
        return validation.latest().orElseThrow(() -> NexoraException.notFound("Validation report", "latest (run POST /api/v1/validation/run first)"));
    }
}
