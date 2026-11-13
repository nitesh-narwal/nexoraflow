package me.niteshh.nexoraflow.employee;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeCostCenterRepository extends JpaRepository<EmployeeCostCenter, String> {

    List<EmployeeCostCenter> findByEmployeeIdOrderByEffectiveFrom(String employeeId);

    /** All cost centers the employee is assigned to (primary or secondary) on the given date. */
    @Query("""
            select a from EmployeeCostCenter a
            where a.employeeId = :employeeId
              and a.effectiveFrom <= :date
              and (a.effectiveTo is null or a.effectiveTo >= :date)
            """)
    List<EmployeeCostCenter> findEffective(@Param("employeeId") String employeeId, @Param("date") LocalDate date);
}
