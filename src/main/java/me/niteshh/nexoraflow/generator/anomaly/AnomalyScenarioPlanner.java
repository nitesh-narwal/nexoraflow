package me.niteshh.nexoraflow.generator.anomaly;

import java.util.List;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.generator.plan.Plans.Plan;
import me.niteshh.nexoraflow.generator.plan.PlanningContext;

/**
 * Plans every deliberately designed anomaly scenario (the seven mandatory ones plus the additional edge cases) on
 * top of the valid baseline. Subjects come from the reserved pool so scenarios and baseline do not disturb each
 * other. Every plan carries an independent ground-truth label derived from its construction.
 */
@Component
public class AnomalyScenarioPlanner {

    public List<Plan> plan(PlanningContext ctx) {
        ScenarioKit kit = new ScenarioKit(ctx);
        LeaveScenarios.plan(kit);
        ExpenseScenarios.plan(kit);
        ApprovalScenarios.plan(kit);
        return kit.plans.stream().sorted(java.util.Comparator.comparing((Plan p) -> p.submitAt).thenComparing(p -> p.key)).toList();
    }
}
