package me.niteshh.nexoraflow.common.rules;

import java.util.List;
import java.util.stream.Collectors;

import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReasonCode;

/**
 * Turns a list of rule outcomes into one decision. Deterministic and free of any randomness:
 * the strongest failed rule wins (HARD &gt; HOLD &gt; FLAG &gt; REVIEW); with no failure the request
 * proceeds to approval.
 */
public final class DecisionAggregator {

    public record Aggregate(Decision decision, List<ReasonCode> reasonCodes) {
        public String reasonCodesAsText() {
            return reasonCodes.stream().map(Enum::name).collect(Collectors.joining(","));
        }
    }

    private DecisionAggregator() {
    }

    public static Aggregate aggregate(List<RuleOutcome> outcomes) {
        List<RuleOutcome> failed = outcomes.stream().filter(RuleOutcome::failed).toList();
        Decision decision;
        if (failed.stream().anyMatch(o -> o.severity() == RuleSeverity.HARD)) {
            decision = Decision.REJECTED;
        } else if (failed.stream().anyMatch(o -> o.severity() == RuleSeverity.HOLD)) {
            decision = Decision.ON_HOLD;
        } else if (failed.stream().anyMatch(o -> o.severity() == RuleSeverity.FLAG)) {
            decision = Decision.FLAGGED;
        } else if (failed.stream().anyMatch(o -> o.severity() == RuleSeverity.REVIEW)) {
            decision = Decision.NEEDS_REVIEW;
        } else {
            decision = Decision.PENDING_APPROVAL;
        }
        List<ReasonCode> codes = failed.stream().map(RuleOutcome::reasonCode).distinct().toList();
        return new Aggregate(decision, codes);
    }
}
