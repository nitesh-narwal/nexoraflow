package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.leave.LeaveEvaluationEngine;
import me.niteshh.nexoraflow.leave.LeaveEvaluationInput;
import me.niteshh.nexoraflow.leave.LeaveEvaluationResult;
import me.niteshh.nexoraflow.leave.LeaveRequest;
import me.niteshh.nexoraflow.policy.AccrualFrequency;
import me.niteshh.nexoraflow.policy.EmployeePolicyAssignment;
import me.niteshh.nexoraflow.policy.LeavePolicy;
import me.niteshh.nexoraflow.policy.LeaveType;

class LeaveEvaluationEngineTest {

    private final LeaveEvaluationEngine engine = new LeaveEvaluationEngine();

    private static Employee employee(EmploymentStatus status, EmploymentType type) {
        Employee e = new Employee();
        e.setEmployeeId("EMP-000001");
        e.setCountry("US");
        e.setEmploymentStatus(status);
        e.setEmploymentType(type);
        e.setJoiningDate(LocalDate.of(2020, 1, 6));
        return e;
    }

    private static LeavePolicy policy() {
        LeavePolicy p = new LeavePolicy();
        p.setLeavePolicyId("LPOL-ANNUAL-US");
        p.setLeaveType(LeaveType.ANNUAL);
        p.setCountry("US");
        p.setAnnualEntitlement(new BigDecimal("15"));
        p.setAccrualFrequency(AccrualFrequency.ANNUAL_UPFRONT);
        p.setMaximumConsecutiveDays(10);
        p.setMinimumNoticeDays(5);
        p.setEligibleEmploymentTypes("FULL_TIME,PART_TIME");
        p.setEffectiveFrom(LocalDate.of(2025, 1, 1));
        return p;
    }

    private static EmployeePolicyAssignment assignment() {
        EmployeePolicyAssignment a = new EmployeePolicyAssignment();
        a.setEffectiveFrom(LocalDate.of(2020, 1, 6));
        return a;
    }

    private LeaveEvaluationResult evaluate(Employee e, LeavePolicy p, LocalDate start, LocalDate end, LocalDate submitted,
                                           BigDecimal available, List<LeaveRequest> overlaps, Set<LocalDate> holidays, String doc) {
        return engine.evaluate(new LeaveEvaluationInput("LVE-2026-000001", e, p, List.of(assignment()), start, end, submitted, doc,
                holidays, available, overlaps));
    }

    private LeaveEvaluationResult valid(LocalDate start, LocalDate end, BigDecimal available) {
        return evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), policy(), start, end, LocalDate.of(2026, 10, 1),
                available, List.of(), Set.of(), null);
    }

    @Test
    void aValidRequestProceedsToApproval() {
        LeaveEvaluationResult r = valid(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6), new BigDecimal("15"));
        assertThat(r.decision()).isEqualTo(Decision.PENDING_APPROVAL);
        assertThat(r.reasonCodes()).isEmpty();
        assertThat(r.businessDays()).isEqualByComparingTo("5");
    }

    @Test
    void insufficientBalanceIsRejected() {
        LeaveEvaluationResult r = valid(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6), new BigDecimal("4.5"));
        assertThat(r.decision()).isEqualTo(Decision.REJECTED);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void exactlyEnoughBalanceIsAccepted() {
        assertThat(valid(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6), new BigDecimal("5")).decision()).isEqualTo(Decision.PENDING_APPROVAL);
    }

    @Test
    void missingBalanceRecordIsInsufficientBalance() {
        assertThat(valid(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3), null).reasonCodes()).contains(ReasonCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void overlapWithApprovedLeaveIsFlaggedNotRejected() {
        LeaveRequest other = new LeaveRequest();
        other.setLeaveRequestId("LVE-2026-000099");
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), policy(), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 4), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(other), Set.of(), null);
        assertThat(r.decision()).isEqualTo(Decision.FLAGGED);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.OVERLAPPING_APPROVED_LEAVE);
    }

    @Test
    void insufficientNoticeIsRejected() {
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), policy(), LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(), null); // 4 days < 5
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.INSUFFICIENT_NOTICE);
    }

    @Test
    void exceedingConsecutiveLimitIsAPolicyLimitViolation() {
        LeaveEvaluationResult r = valid(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 18), new BigDecimal("15")); // 13 business days > 10
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.POLICY_LIMIT_EXCEEDED);
    }

    @Test
    void inactiveEmployeeIsRejected() {
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.INACTIVE, EmploymentType.FULL_TIME), policy(), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(), null);
        assertThat(r.reasonCodes()).contains(ReasonCode.INACTIVE_EMPLOYEE);
        assertThat(r.decision()).isEqualTo(Decision.REJECTED);
    }

    @Test
    void contractorsAreNotEligibleForAnnualLeave() {
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.CONTRACTOR), policy(), LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(), null);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.EMPLOYEE_NOT_ELIGIBLE);
    }

    @Test
    void invertedAndCrossYearRangesAreInvalidAndSkipDependentRules() {
        LeaveEvaluationResult inverted = valid(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 5), new BigDecimal("15"));
        assertThat(inverted.reasonCodes()).containsExactly(ReasonCode.INVALID_DATE_RANGE);
        assertThat(inverted.businessDays()).isNull();
        LeaveEvaluationResult crossYear = valid(LocalDate.of(2026, 12, 29), LocalDate.of(2027, 1, 5), new BigDecimal("15"));
        assertThat(crossYear.reasonCodes()).containsExactly(ReasonCode.INVALID_DATE_RANGE);
    }

    @Test
    void weekendAndHolidayOnlyRangesAreRejected() {
        LeaveEvaluationResult weekend = valid(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8), new BigDecimal("15"));
        assertThat(weekend.reasonCodes()).containsExactly(ReasonCode.HOLIDAY_OR_NON_WORKING_DAY_ONLY);
        LeaveEvaluationResult holiday = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), policy(), LocalDate.of(2026, 12, 25),
                LocalDate.of(2026, 12, 25), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(LocalDate.of(2026, 12, 25)), null);
        assertThat(holiday.reasonCodes()).containsExactly(ReasonCode.HOLIDAY_OR_NON_WORKING_DAY_ONLY);
    }

    @Test
    void holidaysReduceTheRequestedDaysAndTheBalanceNeeded() {
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), policy(), LocalDate.of(2026, 11, 25),
                LocalDate.of(2026, 11, 30), LocalDate.of(2026, 10, 1), new BigDecimal("3"), List.of(), Set.of(LocalDate.of(2026, 11, 26)), null);
        assertThat(r.businessDays()).isEqualByComparingTo("3");
        assertThat(r.decision()).isEqualTo(Decision.PENDING_APPROVAL);
    }

    @Test
    void documentationIsRequiredOnlyAboveTheThreshold() {
        LeavePolicy sick = policy();
        sick.setLeaveType(LeaveType.SICK);
        sick.setCountry(null);
        sick.setRequiresDocumentation(true);
        sick.setDocumentationThresholdDays(2);
        sick.setMinimumNoticeDays(0);
        Employee e = employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME);
        LocalDate day = LocalDate.of(2026, 11, 2);
        assertThat(evaluate(e, sick, day, day.plusDays(1), day, new BigDecimal("10"), List.of(), Set.of(), null).decision()).isEqualTo(Decision.PENDING_APPROVAL);
        LeaveEvaluationResult three = evaluate(e, sick, day, day.plusDays(2), day, new BigDecimal("10"), List.of(), Set.of(), null);
        assertThat(three.decision()).isEqualTo(Decision.NEEDS_REVIEW);
        assertThat(three.reasonCodes()).containsExactly(ReasonCode.DOCUMENTATION_MISSING);
        assertThat(evaluate(e, sick, day, day.plusDays(2), day, new BigDecimal("10"), List.of(), Set.of(), "MED-CERT-1").decision()).isEqualTo(Decision.PENDING_APPROVAL);
    }

    @Test
    void everyEvaluationExplainsWhichRulesPassedAndFailed() {
        LeaveEvaluationResult r = valid(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 18), new BigDecimal("2"));
        assertThat(r.outcomes()).extracting(o -> o.ruleId()).contains("LV-01", "LV-04", "LV-05", "LV-07", "LV-08");
        assertThat(r.outcomes().stream().filter(o -> o.failed()).map(o -> o.ruleId())).containsExactlyInAnyOrder("LV-07", "LV-08");
        assertThat(r.reasonCodes()).containsExactlyInAnyOrder(ReasonCode.POLICY_LIMIT_EXCEEDED, ReasonCode.INSUFFICIENT_BALANCE);
    }

    @Test
    void aPolicyThatExpiresDuringTheRequestIsNotEffective() { // LV-02
        LeavePolicy expiring = policy();
        expiring.setEffectiveTo(LocalDate.of(2026, 11, 4)); // ends in the middle of the requested week
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), expiring, LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 6), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(), null);
        assertThat(r.decision()).isEqualTo(Decision.REJECTED);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.POLICY_NOT_EFFECTIVE);
    }

    @Test
    void aCountrySpecificPolicyIsNotAvailableToEmployeesOfAnotherCountry() { // LV-03
        LeavePolicy german = policy();
        german.setCountry("DE");
        LeaveEvaluationResult r = evaluate(employee(EmploymentStatus.ACTIVE, EmploymentType.FULL_TIME), german, LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 6), LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(), null);
        assertThat(r.decision()).isEqualTo(Decision.REJECTED);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.EMPLOYEE_NOT_ELIGIBLE);
        assertThat(r.outcomes().stream().filter(o -> o.ruleId().equals("LV-08")).findFirst().orElseThrow().verdict().name()).isEqualTo("SKIPPED");
    }

    @Test
    void anEmployeeTerminatedBeforeTheLeaveEndsIsRejected() { // LV-01
        Employee leaver = employee(EmploymentStatus.TERMINATED, EmploymentType.FULL_TIME);
        leaver.setTerminationDate(LocalDate.of(2026, 11, 3));
        LeaveEvaluationResult r = evaluate(leaver, policy(), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6),
                LocalDate.of(2026, 10, 1), new BigDecimal("15"), List.of(), Set.of(), null);
        assertThat(r.decision()).isEqualTo(Decision.REJECTED);
        assertThat(r.reasonCodes()).containsExactly(ReasonCode.INACTIVE_EMPLOYEE);
    }
}
