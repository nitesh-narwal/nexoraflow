package me.niteshh.nexoraflow.generator.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;

/** In-memory snapshot of the persisted organisation, used by planners and the independent approval oracle. */
public final class OrgIndex {

    private final Map<String, Employee> byId = new HashMap<>();
    private final List<Employee> all;

    public OrgIndex(List<Employee> employees) {
        this.all = employees.stream().sorted(Comparator.comparing(Employee::getEmployeeId)).toList();
        all.forEach(e -> byId.put(e.getEmployeeId(), e));
    }

    public List<Employee> all() {
        return all;
    }

    public Employee get(String id) {
        return byId.get(id);
    }

    /** Reporting line, nearest first. */
    public List<Employee> ancestors(Employee e) {
        List<Employee> chain = new ArrayList<>();
        for (String m = e.getManagerId(); m != null; m = byId.get(m).getManagerId()) {
            chain.add(byId.get(m));
        }
        return chain;
    }

    public Optional<Employee> nearestActiveAncestor(Employee e, int maxJobLevel) {
        return ancestors(e).stream()
                .filter(a -> a.getJobLevel() <= maxJobLevel && a.getEmploymentStatus() == EmploymentStatus.ACTIVE).findFirst();
    }

    public List<Employee> activeWithDesignation(String designation) {
        return all.stream().filter(e -> designation.equals(e.getDesignation()) && e.getEmploymentStatus() == EmploymentStatus.ACTIVE).toList();
    }

    public Employee ceo() {
        return all.stream().filter(e -> e.getManagerId() == null).findFirst().orElseThrow();
    }
}
