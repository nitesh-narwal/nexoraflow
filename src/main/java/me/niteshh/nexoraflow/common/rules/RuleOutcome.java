package me.niteshh.nexoraflow.common.rules;

import java.util.LinkedHashMap;
import java.util.Map;

import me.niteshh.nexoraflow.common.ReasonCode;

/**
 * Result of evaluating one business rule. A list of outcomes is the "explanation" of a decision:
 * which rules ran, which passed, which failed and why.
 */
public record RuleOutcome(
        String ruleId,
        String ruleName,
        RuleVerdict verdict,
        RuleSeverity severity,
        ReasonCode reasonCode,
        String message,
        Map<String, Object> details) {

    public static RuleOutcome pass(String ruleId, String ruleName, String message) {
        return new RuleOutcome(ruleId, ruleName, RuleVerdict.PASS, null, null, message, Map.of());
    }

    public static RuleOutcome pass(String ruleId, String ruleName, String message, Map<String, Object> details) {
        return new RuleOutcome(ruleId, ruleName, RuleVerdict.PASS, null, null, message, copy(details));
    }

    public static RuleOutcome skipped(String ruleId, String ruleName, String message) {
        return new RuleOutcome(ruleId, ruleName, RuleVerdict.SKIPPED, null, null, message, Map.of());
    }

    public static RuleOutcome fail(String ruleId, String ruleName, RuleSeverity severity, ReasonCode code,
                                   String message, Map<String, Object> details) {
        return new RuleOutcome(ruleId, ruleName, RuleVerdict.FAIL, severity, code, message, copy(details));
    }

    public static RuleOutcome fail(String ruleId, String ruleName, RuleSeverity severity, ReasonCode code, String message) {
        return fail(ruleId, ruleName, severity, code, message, Map.of());
    }

    public boolean failed() {
        return verdict == RuleVerdict.FAIL;
    }

    public boolean passed() {
        return verdict == RuleVerdict.PASS;
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
