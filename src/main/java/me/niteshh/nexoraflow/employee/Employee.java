package me.niteshh.nexoraflow.employee;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.PersistableEntity;

/**
 * Employee master record. Other modules reference employees by business ID only; the organisational
 * hierarchy is the self-reference {@code managerId}, guarded against cycles by a database trigger.
 */
@Entity
@Table(name = "employee")
@Getter
@Setter
@NoArgsConstructor
public class Employee extends PersistableEntity {

    @Id
    @Column(name = "employee_id")
    private String employeeId;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;

    private String phone;

    @Column(name = "department_id")
    private String departmentId;

    private String designation;

    /** 1 = CEO, 2 = department head, 3 = manager, 4 = team lead, 5 = individual contributor. */
    @Column(name = "job_level")
    private int jobLevel;

    @Column(name = "manager_id")
    private String managerId;

    private String country;

    private String location;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type")
    private EmploymentType employmentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_status")
    private EmploymentStatus employmentStatus;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "termination_date")
    private LocalDate terminationDate;

    @Column(name = "cost_center_id")
    private String costCenterId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = AppClock.currentInstant();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = AppClock.currentInstant();
    }

    @Override
    public String getId() {
        return employeeId;
    }

    public String fullName() {
        return firstName + " " + lastName;
    }
}
