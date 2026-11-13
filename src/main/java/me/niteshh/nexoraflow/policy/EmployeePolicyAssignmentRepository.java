package me.niteshh.nexoraflow.policy;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeePolicyAssignmentRepository extends JpaRepository<EmployeePolicyAssignment, String> {

    List<EmployeePolicyAssignment> findByEmployeeIdOrderByEffectiveFrom(String employeeId);

    List<EmployeePolicyAssignment> findByEmployeeIdAndLeavePolicyId(String employeeId, String leavePolicyId);
}
