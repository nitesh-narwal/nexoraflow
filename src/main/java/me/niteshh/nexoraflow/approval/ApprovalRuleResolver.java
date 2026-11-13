package me.niteshh.nexoraflow.approval;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.common.RequestType;

/**
 * Selects, per approval level, the single applicable approval rule from the configured rule table.
 * Nothing about thresholds is hard-coded here: changing an approval threshold means changing rows.
 *
 * <p>A rule applies when it is ACTIVE, effective on the date, its scope filters (country, department,
 * category) match, and {@code minimumAmount < amount <= maximumAmount}. When several rules of the same
 * level apply, the lowest {@code priority} number wins, then the most specific rule, then the lowest ID.
 */
@Component
public class ApprovalRuleResolver {

    private final ApprovalRuleRepository rules;

    public ApprovalRuleResolver(ApprovalRuleRepository rules) {
        this.rules = rules;
    }

    public List<ApprovalRule> resolve(RoutingContext ctx) {
        List<ApprovalRule> candidates = rules.findByWorkflowTypeAndRuleStatus(ctx.requestType(), ApprovalRule.Status.ACTIVE);
        return select(candidates, ctx);
    }

    /** Pure selection logic (unit-testable without a database). Result is ordered by approval level. */
    public static List<ApprovalRule> select(List<ApprovalRule> candidates, RoutingContext ctx) {
        Map<Integer, List<ApprovalRule>> byLevel = candidates.stream()
                .filter(r -> r.getRuleStatus() == ApprovalRule.Status.ACTIVE)
                .filter(r -> r.getWorkflowType() == ctx.requestType())
                .filter(r -> !ctx.effectiveDate().isBefore(r.getEffectiveFrom())
                        && (r.getEffectiveTo() == null || !ctx.effectiveDate().isAfter(r.getEffectiveTo())))
                .filter(r -> r.getCountry() == null || r.getCountry().equals(ctx.requester().getCountry()))
                .filter(r -> r.getDepartmentId() == null || r.getDepartmentId().equals(ctx.requester().getDepartmentId()))
                .filter(r -> r.getExpenseCategory() == null
                        || (ctx.requestType() == RequestType.EXPENSE && ctx.categories().contains(r.getExpenseCategory())))
                .filter(r -> inRange(r, ctx))
                .collect(Collectors.groupingBy(ApprovalRule::getApprovalLevel, TreeMap::new, Collectors.toList()));

        return byLevel.values().stream()
                .map(list -> list.stream().min(
                        Comparator.comparingInt(ApprovalRule::getPriority)
                                .thenComparing(Comparator.comparingInt(ApprovalRule::specificity).reversed())
                                .thenComparing(ApprovalRule::getApprovalRuleId)).orElseThrow())
                .toList();
    }

    static boolean inRange(ApprovalRule rule, RoutingContext ctx) {
        boolean aboveMin = ctx.amount().compareTo(rule.getMinimumAmount()) > 0;
        boolean belowMax = rule.getMaximumAmount() == null || ctx.amount().compareTo(rule.getMaximumAmount()) <= 0;
        return aboveMin && belowMax;
    }
}
