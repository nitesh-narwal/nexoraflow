package me.niteshh.nexoraflow.costcenter;

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
@Table(name = "cost_center")
@Getter
@Setter
@NoArgsConstructor
public class CostCenter extends PersistableEntity {

    @Id
    @Column(name = "cost_center_id")
    private String costCenterId;

    @Column(name = "cost_center_code")
    private String costCenterCode;

    @Column(name = "cost_center_name")
    private String costCenterName;

    @Column(name = "department_id")
    private String departmentId;

    @Column(name = "budget_owner_id")
    private String budgetOwnerId;

    private String country;

    @Enumerated(EnumType.STRING)
    private CostCenterStatus status = CostCenterStatus.ACTIVE;

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
        return costCenterId;
    }

    public boolean isActive() {
        return status == CostCenterStatus.ACTIVE;
    }
}
