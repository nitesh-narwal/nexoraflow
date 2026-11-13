package me.niteshh.nexoraflow.policy;

import me.niteshh.nexoraflow.employee.EmploymentType;

/** Employee category an expense policy applies to. */
public enum PolicyEmployeeType {
    ALL,
    FULL_TIME,
    PART_TIME,
    CONTRACTOR,
    INTERN;

    public boolean appliesTo(EmploymentType type) {
        return this == ALL || this.name().equals(type.name());
    }
}
