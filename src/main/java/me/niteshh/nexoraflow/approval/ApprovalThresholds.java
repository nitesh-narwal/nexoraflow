package me.niteshh.nexoraflow.approval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.common.RequestType;

/**
 * The approval-tier boundaries currently configured for expenses (USD-equivalent), read from the active,
 * effective approval rules. Used by split-claim detection so that no threshold is hard-coded.
 */
@Component
public class ApprovalThresholds {

    private final ApprovalRuleRepository rules;

    public ApprovalThresholds(ApprovalRuleRepository rules) {
        this.rules = rules;
    }

    /** Distinct upper bounds of the active expense rules, ascending (e.g. 500, 2000, 5000). */
    public List<BigDecimal> expenseBoundaries(LocalDate date) {
        return rules.findByWorkflowTypeAndRuleStatus(RequestType.EXPENSE, ApprovalRule.Status.ACTIVE).stream()
                .filter(r -> !date.isBefore(r.getEffectiveFrom()) && (r.getEffectiveTo() == null || !date.isAfter(r.getEffectiveTo())))
                .map(ApprovalRule::getMaximumAmount)
                .filter(java.util.Objects::nonNull)
                .map(b -> b.setScale(2, java.math.RoundingMode.HALF_UP))
                .distinct()
                .sorted()
                .toList();
    }
}
