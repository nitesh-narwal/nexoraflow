package me.niteshh.nexoraflow.employee;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** Effective-dated assignment of an employee to a cost center (history is preserved). */
@Entity
@Table(name = "employee_cost_center")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeCostCenter extends PersistableEntity {

    @Id
    @Column(name = "assignment_id")
    private String assignmentId;

    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "cost_center_id")
    private String costCenterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_type")
    private AssignmentType assignmentType;

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

    public boolean coversDate(LocalDate date) {
        return !date.isBefore(effectiveFrom) && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}
