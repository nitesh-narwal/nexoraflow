package me.niteshh.nexoraflow.generator.master;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;

/**
 * Employees held back from the baseline workload so that anomaly scenarios are not disturbed by (and do
 * not disturb) baseline requests: individual contributors with a fully active reporting line, plus every
 * employee whose reporting line contains an unavailable manager. Membership is a pure function of the master
 * data, so baseline planners and anomaly planners always agree on it.
 */
public final class ReservedPool {

    private final Set<String> scenarioPool = new HashSet<>();
    private final Set<String> unavailableChain = new HashSet<>();
    /** The CEO has no manager, so no approver can ever be resolved for the CEO's requests. */
    private final Set<String> withoutApprover = new HashSet<>();
    private final List<Employee> poolMembers = new ArrayList<>();

    public ReservedPool(List<Employee> employees) {
        Map<String, Employee> byId = new HashMap<>();
        employees.forEach(e -> byId.put(e.getEmployeeId(), e));

        List<Employee> candidates = new ArrayList<>();
        for (Employee e : employees) {
            if (e.getManagerId() == null) {
                withoutApprover.add(e.getEmployeeId());
                continue;
            }
            boolean inactiveAncestor = false;
            for (String m = e.getManagerId(); m != null; m = byId.get(m).getManagerId()) {
                if (byId.get(m).getEmploymentStatus() != EmploymentStatus.ACTIVE) {
                    inactiveAncestor = true;
                    break;
                }
            }
            if (inactiveAncestor) {
                unavailableChain.add(e.getEmployeeId());
                continue;
            }
            if (e.getJobLevel() == 5 && e.getEmploymentStatus() == EmploymentStatus.ACTIVE
                    && e.getEmploymentType() == EmploymentType.FULL_TIME
                    && e.getJoiningDate().isBefore(LocalDate.of(2025, 12, 31))) {
                candidates.add(e);
            }
        }
        candidates.sort(Comparator.comparing(Employee::getEmployeeId));
        for (int i = 0; i < candidates.size(); i++) {
            if (i % 4 == 1) {
                scenarioPool.add(candidates.get(i).getEmployeeId());
                poolMembers.add(candidates.get(i));
            }
        }
    }

    /** True when the employee must not receive baseline requests. */
    public boolean isReserved(String employeeId) {
        return scenarioPool.contains(employeeId) || unavailableChain.contains(employeeId) || withoutApprover.contains(employeeId);
    }

    /** Employees available to anomaly scenarios (ordered by ID). */
    public List<Employee> members() {
        return poolMembers;
    }

    /** Active full-time individual contributors whose DIRECT manager is unavailable (escalation scenarios). */
    public List<Employee> orphans(List<Employee> employees) {
        Map<String, Employee> byId = new HashMap<>();
        employees.forEach(e -> byId.put(e.getEmployeeId(), e));
        return employees.stream()
                .filter(e -> e.getManagerId() != null && byId.get(e.getManagerId()).getEmploymentStatus() != EmploymentStatus.ACTIVE
                        && e.getJobLevel() == 5 && e.getEmploymentStatus() == EmploymentStatus.ACTIVE
                        && e.getEmploymentType() == EmploymentType.FULL_TIME && e.getJoiningDate().isBefore(LocalDate.of(2025, 12, 31)))
                .sorted(Comparator.comparing(Employee::getEmployeeId)).toList();
    }

    /** Employees reporting (directly or indirectly) to an unavailable manager. */
    public Set<String> unavailableChain() {
        return unavailableChain;
    }
}
