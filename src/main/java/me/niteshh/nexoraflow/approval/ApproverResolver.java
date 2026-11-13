package me.niteshh.nexoraflow.approval;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.employee.EmployeeRules;

/**
 * Locates the concrete approver for a rule role using the organisational hierarchy.
 *
 * <p>Requesters are never eligible to approve their own request, and inactive / terminated employees are
 * never eligible. When the natural approver is unavailable the resolver escalates up the reporting line
 * and reports that it did so.
 */
@Component
public class ApproverResolver {

    public static final String DEFAULT_FINANCE_REVIEWER_DESIGNATION = "Finance Manager";
    public static final String DEFAULT_FINANCE_HEAD_DESIGNATION = "VP Finance";
    static final int MANAGER_LEVEL = 3;
    static final int DEPARTMENT_HEAD_LEVEL = 2;
    private static final int MAX_CHAIN = 12;

    public record Resolution(Employee approver, boolean escalated, String escalatedFromEmployeeId, String note) {
    }

    private final EmployeeRepository employees;

    public ApproverResolver(EmployeeRepository employees) {
        this.employees = employees;
    }

    public Optional<Resolution> resolve(ApproverRole role, String designation, Employee requester, LocalDate date) {
        return switch (role) {
            case DIRECT_MANAGER -> walkUp(requester, e -> true, date);
            case MANAGER -> walkUp(requester, e -> e.getJobLevel() <= MANAGER_LEVEL, date);
            case DEPARTMENT_HEAD -> walkUp(requester, e -> e.getJobLevel() <= DEPARTMENT_HEAD_LEVEL, date);
            case FINANCE_REVIEWER -> byDesignation(designation != null ? designation : DEFAULT_FINANCE_REVIEWER_DESIGNATION,
                    requester, date);
            case FINANCE_HEAD -> byDesignation(designation != null ? designation : DEFAULT_FINANCE_HEAD_DESIGNATION,
                    requester, date);
        };
    }

    /** Reporting line of the employee, nearest first. Cycle-safe and bounded. */
    public List<Employee> ancestors(Employee employee) {
        List<Employee> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(employee.getEmployeeId());
        String next = employee.getManagerId();
        while (next != null && chain.size() < MAX_CHAIN && seen.add(next)) {
            Optional<Employee> manager = employees.findById(next);
            if (manager.isEmpty()) {
                break;
            }
            chain.add(manager.get());
            next = manager.get().getManagerId();
        }
        return chain;
    }

    public boolean isAncestor(Employee requester, String candidateId) {
        return ancestors(requester).stream().anyMatch(a -> a.getEmployeeId().equals(candidateId));
    }

    private Optional<Resolution> walkUp(Employee requester, Predicate<Employee> qualifies, LocalDate date) {
        String skippedQualified = null;
        for (Employee candidate : ancestors(requester)) {
            if (!qualifies.test(candidate)) {
                continue;
            }
            if (EmployeeRules.canActOn(candidate, date) && !candidate.getEmployeeId().equals(requester.getEmployeeId())) {
                boolean escalated = skippedQualified != null;
                return Optional.of(new Resolution(candidate, escalated, skippedQualified,
                        escalated ? "Escalated from unavailable approver " + skippedQualified : null));
            }
            if (skippedQualified == null) {
                skippedQualified = candidate.getEmployeeId();
            }
        }
        return Optional.empty();
    }

    private Optional<Resolution> byDesignation(String designation, Employee requester, LocalDate date) {
        return employees.findByDesignationAndEmploymentStatusOrderByEmployeeId(designation,
                        me.niteshh.nexoraflow.employee.EmploymentStatus.ACTIVE).stream()
                .filter(e -> EmployeeRules.canActOn(e, date))
                .filter(e -> !e.getEmployeeId().equals(requester.getEmployeeId()))
                .min(Comparator
                        .comparing((Employee e) -> !e.getCountry().equals(requester.getCountry()))
                        .thenComparing(Employee::getEmployeeId))
                .map(e -> new Resolution(e, false, null, null));
    }
}
