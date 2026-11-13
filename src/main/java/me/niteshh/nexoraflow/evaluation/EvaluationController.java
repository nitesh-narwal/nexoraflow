package me.niteshh.nexoraflow.evaluation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.niteshh.nexoraflow.common.exception.NexoraException;

@RestController
@RequestMapping("/api/v1/evaluation")
@Tag(name = "Generation and evaluation")
public class EvaluationController {

    private final EvaluationService evaluation;

    public EvaluationController(EvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @PostMapping("/run")
    @Operation(summary = "Compare engine decisions with the independent ground truth and store the report")
    public EvaluationReport run() {
        return evaluation.evaluateAndStore();
    }

    @GetMapping("/report")
    @Operation(summary = "Latest evaluation report (precision / recall / F1, decision accuracy, mismatches)")
    public EvaluationReport report() {
        return evaluation.latest().orElseThrow(() -> NexoraException.notFound("Evaluation report", "latest (run POST /api/v1/evaluation/run first)"));
    }
}
