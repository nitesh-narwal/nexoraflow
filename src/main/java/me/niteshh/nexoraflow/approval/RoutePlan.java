package me.niteshh.nexoraflow.approval;

import java.util.List;

import me.niteshh.nexoraflow.common.ReasonCode;

/** Result of route planning: either a resolvable list of steps or a failure with the reason. */
public record RoutePlan(boolean resolvable, List<PlannedStep> steps, ReasonCode failureCode, String failureMessage) {

    public static RoutePlan of(List<PlannedStep> steps) {
        return new RoutePlan(true, List.copyOf(steps), null, null);
    }

    public static RoutePlan failed(ReasonCode code, String message) {
        return new RoutePlan(false, List.of(), code, message);
    }

    /** No route is needed (e.g. the request was rejected before reaching approval). */
    public static RoutePlan notApplicable() {
        return new RoutePlan(false, List.of(), null, "Not applicable");
    }

    /** Human-readable path such as "DIRECT_MANAGER(EMP-000012) > FINANCE_REVIEWER(EMP-000007)". */
    public String describe() {
        if (!resolvable) {
            return failureCode == null ? "N/A" : "UNRESOLVABLE: " + failureMessage;
        }
        return steps.stream().filter(s -> !s.skipped())
                .map(s -> s.role() + "(" + s.approverEmployeeId() + ")")
                .reduce((a, b) -> a + " > " + b).orElse("");
    }

    public boolean anyEscalated() {
        return steps.stream().anyMatch(PlannedStep::escalated);
    }
}
