package me.niteshh.nexoraflow.employee;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {

    boolean existsByEmailIgnoreCase(String email);

    List<Employee> findByManagerIdOrderByEmployeeId(String managerId);

    List<Employee> findByDepartmentIdOrderByEmployeeId(String departmentId);

    List<Employee> findByDesignationAndEmploymentStatusOrderByEmployeeId(String designation, EmploymentStatus status);

    @Query("select e from Employee e order by e.employeeId")
    List<Employee> findAllOrdered();
}
