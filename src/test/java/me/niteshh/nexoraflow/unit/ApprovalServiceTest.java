package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.approval.ApprovalOutcomeHandler;
import me.niteshh.nexoraflow.approval.ApprovalRuleRepository;
import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.approval.ApprovalStatus;
import me.niteshh.nexoraflow.approval.ApprovalStep;
import me.niteshh.nexoraflow.approval.ApprovalStepRepository;
import me.niteshh.nexoraflow.approval.ApproverResolver;
import me.niteshh.nexoraflow.approval.ApproverRole;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.employee.EmploymentStatus;

/** Approval restrictions with all collaborators mocked: no database, no Spring. */
class ApprovalServiceTest {

    private ApprovalStepRepository steps;
    private EmployeeRepository employees;
    private ApproverResolver resolver;
    private AuditService audit;
    private ApprovalOutcomeHandler handler;
    private ApprovalService service;
    private ApprovalStep step;

    private static Employee employee(String id, int level, EmploymentStatus status) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setJobLevel(level);
        e.setEmploymentStatus(status);
        e.setJoiningDate(LocalDate.of(2020, 1, 1));
        e.setDesignation("Engineering Manager");
        return e;
    }

    @BeforeEach
    void setUp() {
        AppClock.setSimulatedTime(Instant.parse("2026-10-22T10:00:00Z"));
        steps = mock(ApprovalStepRepository.class);
        employees = mock(EmployeeRepository.class);
        resolver = mock(ApproverResolver.class);
        audit = mock(AuditService.class);
        handler = mock(ApprovalOutcomeHandler.class);
        when(handler.requestType()).thenReturn(RequestType.EXPENSE);
        when(handler.requesterOf("EXP-1")).thenReturn(Optional.of("EMP-REQ"));
        when(handler.awaitingApproval("EXP-1")).thenReturn(true);
        service = new ApprovalService(steps, mock(ApprovalRuleRepository.class), employees, resolver, audit, new AppClock(), List.of(handler));

        step = new ApprovalStep();
        step.setApprovalStepId("APR-EXP-1-01");
        step.setRequestType(RequestType.EXPENSE);
        step.setExpenseClaimId("EXP-1");
        step.setStepSequence(1);
        step.setApprovalLevel(1);
        step.setApproverRole(ApproverRole.MANAGER);
        step.setApproverEmployeeId("EMP-MGR");
        step.setApprovalStatus(ApprovalStatus.PENDING);
        when(steps.findById("APR-EXP-1-01")).thenReturn(Optional.of(step));
        when(steps.findByExpenseClaimIdOrderByStepSequence("EXP-1")).thenReturn(List.of(step));
        when(employees.findById("EMP-REQ")).thenReturn(Optional.of(employee("EMP-REQ", 5, EmploymentStatus.ACTIVE)));
        when(employees.findById("EMP-MGR")).thenReturn(Optional.of(employee("EMP-MGR", 3, EmploymentStatus.ACTIVE)));
        when(resolver.isAncestor(any(), anyString())).thenReturn(true);
    }

    private ApprovalService.ActionCommand by(String actor) {
        return new ApprovalService.ActionCommand(actor, "c", "r");
    }

    @Test
    void requesterCannotApproveTheirOwnRequestEvenWhenTheyAreTheAssignedApprover() {
        step.setApproverEmployeeId("EMP-REQ"); // pathological data: still blocked
        var result = service.approve("APR-EXP-1-01", by("EMP-REQ"));
        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.SELF_APPROVAL_NOT_ALLOWED);
        assertThat(step.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
        verify(handler, never()).onFinalApproval(anyString(), anyString());
        verify(audit).record(any(), anyString(), anyString(), org.mockito.ArgumentMatchers.eq("APPROVAL_ATTEMPT_REJECTED"), any(), any(),
                org.mockito.ArgumentMatchers.eq("EMP-REQ"), org.mockito.ArgumentMatchers.eq("SELF_APPROVAL_NOT_ALLOWED"), any());
    }

    @Test
    void inactiveEmployeesCannotApprove() {
        when(employees.findById("EMP-MGR")).thenReturn(Optional.of(employee("EMP-MGR", 3, EmploymentStatus.INACTIVE)));
        var result = service.approve("APR-EXP-1-01", by("EMP-MGR"));
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.APPROVER_INACTIVE);
        assertThat(step.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void onlyTheAssignedApproverMayAct() {
        when(employees.findById("EMP-OTHER")).thenReturn(Optional.of(employee("EMP-OTHER", 3, EmploymentStatus.ACTIVE)));
        assertThat(service.approve("APR-EXP-1-01", by("EMP-OTHER")).reasonCode()).isEqualTo(ReasonCode.UNAUTHORIZED_APPROVER);
        assertThat(service.approve("APR-EXP-1-01", by("EMP-GHOST")).reasonCode()).isEqualTo(ReasonCode.UNAUTHORIZED_APPROVER);
    }

    @Test
    void approverWithoutTheRequiredJobLevelLacksAuthority() {
        step.setApproverEmployeeId("EMP-JUNIOR");
        when(employees.findById("EMP-JUNIOR")).thenReturn(Optional.of(employee("EMP-JUNIOR", 5, EmploymentStatus.ACTIVE)));
        assertThat(service.approve("APR-EXP-1-01", by("EMP-JUNIOR")).reasonCode()).isEqualTo(ReasonCode.UNAUTHORIZED_APPROVER);
    }

    @Test
    void completedStepsCannotBeOverwritten() {
        step.setApprovalStatus(ApprovalStatus.APPROVED);
        assertThat(service.approve("APR-EXP-1-01", by("EMP-MGR")).reasonCode()).isEqualTo(ReasonCode.STEP_ALREADY_COMPLETED);
        assertThat(service.reject("APR-EXP-1-01", by("EMP-MGR")).reasonCode()).isEqualTo(ReasonCode.STEP_ALREADY_COMPLETED);
        assertThat(step.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void stepsMustRunInOrder() {
        step.setApprovalStatus(ApprovalStatus.WAITING);
        assertThat(service.approve("APR-EXP-1-01", by("EMP-MGR")).reasonCode()).isEqualTo(ReasonCode.INVALID_STEP_ORDER);
    }

    @Test
    void requestsThatAreNotAwaitingApprovalRejectActions() {
        when(handler.awaitingApproval("EXP-1")).thenReturn(false);
        assertThat(service.approve("APR-EXP-1-01", by("EMP-MGR")).reasonCode()).isEqualTo(ReasonCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void theAssignedActiveApproverCompletesTheStepAndTriggersFinalApproval() {
        var result = service.approve("APR-EXP-1-01", by("EMP-MGR"));
        assertThat(result.accepted()).isTrue();
        assertThat(step.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(step.getActionAt()).isNotNull();
        verify(handler).onFinalApproval("EXP-1", "EMP-MGR");
    }

    @Test
    void aRejectionRejectsTheRequestAndCancelsTheRemainingSteps() {
        ApprovalStep second = new ApprovalStep();
        second.setApprovalStepId("APR-EXP-1-02");
        second.setRequestType(RequestType.EXPENSE);
        second.setExpenseClaimId("EXP-1");
        second.setStepSequence(2);
        second.setApprovalLevel(2);
        second.setApprovalStatus(ApprovalStatus.WAITING);
        when(steps.findByExpenseClaimIdOrderByStepSequence("EXP-1")).thenReturn(List.of(step, second));
        var result = service.reject("APR-EXP-1-01", by("EMP-MGR"));
        assertThat(result.accepted()).isTrue();
        assertThat(step.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(second.getApprovalStatus()).isEqualTo(ApprovalStatus.CANCELLED);
        verify(handler).onRejected(anyString(), anyString(), any());
        verify(handler, never()).onFinalApproval(anyString(), anyString());
    }
}
