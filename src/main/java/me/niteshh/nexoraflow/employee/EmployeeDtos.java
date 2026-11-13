package me.niteshh.nexoraflow.employee;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** API representations of employees. JPA entities are never exposed directly. */
public final class EmployeeDtos {

    private EmployeeDtos() {
    }

    public record EmployeeResponse(String employeeId, String firstName, String lastName, String email, String phone,
                                   String departmentId, String designation, int jobLevel, String managerId, String country,
                                   String location, EmploymentType employmentType, EmploymentStatus employmentStatus,
                                   LocalDate joiningDate, LocalDate terminationDate, String costCenterId) {
        public static EmployeeResponse from(Employee e) {
            return new EmployeeResponse(e.getEmployeeId(), e.getFirstName(), e.getLastName(), e.getEmail(), e.getPhone(),
                    e.getDepartmentId(), e.getDesignation(), e.getJobLevel(), e.getManagerId(), e.getCountry(),
                    e.getLocation(), e.getEmploymentType(), e.getEmploymentStatus(), e.getJoiningDate(),
                    e.getTerminationDate(), e.getCostCenterId());
        }
    }

    public record CreateEmployeeRequest(
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotBlank @Email @Size(max = 160) String email,
            @Size(max = 40) String phone,
            @NotBlank String departmentId,
            @NotBlank @Size(max = 80) String designation,
            @Min(1) @Max(5) int jobLevel,
            String managerId,
            @NotBlank @Size(min = 2, max = 2) String country,
            @Size(max = 80) String location,
            @NotNull EmploymentType employmentType,
            @NotNull LocalDate joiningDate,
            String costCenterId) {
    }
}
