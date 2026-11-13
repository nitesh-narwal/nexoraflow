package me.niteshh.nexoraflow.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

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
import me.niteshh.nexoraflow.employee.EmploymentType;

@Entity
@Table(name = "leave_policy")
@Getter
@Setter
@NoArgsConstructor
public class LeavePolicy extends PersistableEntity {

    @Id
    @Column(name = "leave_policy_id")
    private String leavePolicyId;

    @Column(name = "policy_name")
    private String policyName;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type")
    private LeaveType leaveType;

    /** Null means the policy applies to every country. */
    private String country;

    @Column(name = "annual_entitlement")
    private BigDecimal annualEntitlement;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_frequency")
    private AccrualFrequency accrualFrequency;

    @Column(name = "carry_forward_limit")
    private BigDecimal carryForwardLimit = BigDecimal.ZERO;

    @Column(name = "maximum_consecutive_days")
    private int maximumConsecutiveDays;

    @Column(name = "minimum_notice_days")
    private int minimumNoticeDays;

    @Column(name = "requires_documentation")
    private boolean requiresDocumentation;

    /** Documentation is required when the request spans MORE business days than this. */
    @Column(name = "documentation_threshold_days")
    private int documentationThresholdDays;

    /** Comma separated {@link EmploymentType} names. */
    @Column(name = "eligible_employment_types")
    private String eligibleEmploymentTypes;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_status")
    private PolicyStatus policyStatus = PolicyStatus.ACTIVE;

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
        return leavePolicyId;
    }

    public Set<EmploymentType> eligibleTypes() {
        if (eligibleEmploymentTypes == null || eligibleEmploymentTypes.isBlank()) {
            return EnumSet.noneOf(EmploymentType.class);
        }
        return Arrays.stream(eligibleEmploymentTypes.split(","))
                .map(String::trim).map(EmploymentType::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(EmploymentType.class)));
    }

    public boolean isActive() {
        return policyStatus == PolicyStatus.ACTIVE;
    }

    /** True when the policy is ACTIVE and its effective window covers the whole [start, end] range. */
    public boolean isEffectiveDuring(LocalDate start, LocalDate end) {
        return isActive() && !start.isBefore(effectiveFrom) && (effectiveTo == null || !end.isAfter(effectiveTo));
    }
}
