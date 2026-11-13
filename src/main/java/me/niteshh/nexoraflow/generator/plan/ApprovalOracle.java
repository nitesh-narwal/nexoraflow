package me.niteshh.nexoraflow.generator.plan;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.generator.catalog.BusinessPolicyBaseline;

/**
 * Independent re-statement of the documented approval policy, used ONLY to derive expected approval paths for the
 * ground truth. It is written from the business requirement (thresholds in {@link BusinessPolicyBaseline}) and the
 * organisation chart - it shares no code with the engine's rule resolver.
 */
public final class ApprovalOracle {

    public record ExpectedStep(String role, String approverId) {
    }

    private final OrgIndex org;

    public ApprovalOracle(OrgIndex org) {
        this.org = org;
    }

    /** Roles required for an expense of the given USD-equivalent amount, in approval order. */
    public static List<String> expenseRoles(BigDecimal amountUsd, boolean clientEntertainment) {
        List<BigDecimal> b = BusinessPolicyBaseline.EXPENSE_BOUNDARIES_USD;
        List<String> roles = new ArrayList<>();
        if (amountUsd.compareTo(b.get(0)) <= 0) {
            roles.add("DIRECT_MANAGER");
        } else if (amountUsd.compareTo(b.get(1)) <= 0) {
            roles.add("MANAGER");
        } else if (amountUsd.compareTo(b.get(2)) <= 0) {
            roles.add("MANAGER");
            roles.add("FINANCE_REVIEWER");
        } else {
            roles.add("MANAGER");
            roles.add("FINANCE_HEAD");
        }
        if (clientEntertainment && amountUsd.compareTo(BusinessPolicyBaseline.CLIENT_ENTERTAINMENT_FINANCE_REVIEW_UP_TO_USD) <= 0) {
            roles.add("FINANCE_REVIEWER");
        }
        return roles;
    }

    public static List<String> leaveRoles(int businessDays) {
        return businessDays > BusinessPolicyBaseline.LONG_LEAVE_DAYS ? List.of("DIRECT_MANAGER", "DEPARTMENT_HEAD") : List.of("DIRECT_MANAGER");
    }

    /** Concrete approvers for a role list, collapsing repeated approvers exactly as the policy states. */
    public List<ExpectedStep> resolve(Employee requester, List<String> roles) {
        List<ExpectedStep> steps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String role : roles) {
            Optional<Employee> approver = switch (role) {
                // the direct manager, or - when unavailable - the nearest active manager above (escalation)
                case "DIRECT_MANAGER" -> org.ancestors(requester).stream()
                        .filter(a -> a.getEmploymentStatus() == me.niteshh.nexoraflow.employee.EmploymentStatus.ACTIVE).findFirst();
                case "MANAGER" -> org.nearestActiveAncestor(requester, 3);
                case "DEPARTMENT_HEAD" -> org.nearestActiveAncestor(requester, 2);
                case "FINANCE_REVIEWER" -> org.activeWithDesignation(BusinessPolicyBaseline.FINANCE_MANAGER).stream()
                        .filter(e -> !e.getEmployeeId().equals(requester.getEmployeeId()))
                        .min(Comparator.comparing((Employee e) -> !e.getCountry().equals(requester.getCountry()))
                                .thenComparing(Employee::getEmployeeId));
                case "FINANCE_HEAD" -> org.activeWithDesignation(BusinessPolicyBaseline.FINANCE_HEAD).stream().findFirst();
                default -> Optional.empty();
            };
            if (approver.isPresent() && seen.add(approver.get().getEmployeeId())) {
                steps.add(new ExpectedStep(role, approver.get().getEmployeeId()));
            }
        }
        return steps;
    }

    public static String describe(List<ExpectedStep> steps) {
        return steps.stream().map(s -> s.role() + "(" + s.approverId() + ")").reduce((a, c) -> a + " > " + c).orElse("");
    }
}
