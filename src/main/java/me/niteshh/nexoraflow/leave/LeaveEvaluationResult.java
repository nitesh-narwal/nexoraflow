package me.niteshh.nexoraflow.leave;

import java.math.BigDecimal;
import java.util.List;

import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;

public record LeaveEvaluationResult(
        List<RuleOutcome> outcomes,
        Decision decision,
        List<ReasonCode> reasonCodes,
        BigDecimal businessDays) {
}
