package me.niteshh.nexoraflow.policy;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** Records that an employee is eligible for (assigned to) a leave policy during a period. */
@Entity
@Table(name = "employee_policy_assignment")
@Getter
@Setter
@NoArgsConstructor
public class EmployeePolicyAssignment extends PersistableEntity {

    @Id
    @Column(name = "assignment_id")
    private String assignmentId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "leave_policy_id")
    private String leavePolicyId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = AppClock.currentInstant();
        }
    }

    @Override
    public String getId() {
        return assignmentId;
    }

    public boolean coversRange(LocalDate start, LocalDate end) {
        return !start.isBefore(effectiveFrom) && (effectiveTo == null || !end.isAfter(effectiveTo));
    }
}
