package me.niteshh.nexoraflow.employee;

import java.time.LocalDate;

/** Pure employment-period predicates shared by the leave, expense and approval engines. */
public final class EmployeeRules {

    private EmployeeRules() {
    }

    /** True when the date lies inside [joining_date, termination_date] (open ended when not terminated). */
    public static boolean employedOn(Employee employee, LocalDate date) {
        if (date.isBefore(employee.getJoiningDate())) {
            return false;
        }
        return employee.getTerminationDate() == null || !date.isAfter(employee.getTerminationDate());
    }

    /** Only ACTIVE employees can act (request leave, approve). */
    public static boolean isActive(Employee employee) {
        return employee.getEmploymentStatus() == EmploymentStatus.ACTIVE;
    }

    /** Active and still employed on the given date. */
    public static boolean canActOn(Employee employee, LocalDate date) {
        return isActive(employee) && employedOn(employee, date);
    }
}
