package me.niteshh.nexoraflow.department;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "department")
@Getter
@Setter
@NoArgsConstructor
public class Department extends PersistableEntity {

    public enum Status { ACTIVE, INACTIVE }

    @Id
    @Column(name = "department_id")
    private String departmentId;

    @Column(name = "department_name")
    private String departmentName;

    @Column(name = "head_employee_id")
    private String headEmployeeId;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

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
        return departmentId;
    }
}
