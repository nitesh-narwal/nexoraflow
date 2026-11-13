package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import me.niteshh.nexoraflow.common.rules.DecisionAggregator;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

public record ExpenseEvaluationResult(
        List<RuleOutcome> outcomes,
        DecisionAggregator.Aggregate aggregate,
        List<LineResult> lines,
        BigDecimal totalReporting,
        Set<ExpenseCategory> categories,
        Optional<SplitClaimDetector.Finding> splitFinding) {
}
