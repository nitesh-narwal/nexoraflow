package me.niteshh.nexoraflow.employee;

import java.net.URI;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.leave.LeaveDtos;

@RestController
@RequestMapping("/api/v1/employees")
@Tag(name = "Employees", description = "Employee master data and leave balances")
public class EmployeeController {

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @Operation(summary = "List employees (filter by department, country, status, manager)")
    public PageResponse<EmployeeDtos.EmployeeResponse> list(
            @RequestParam(required = false) String departmentId,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) EmploymentStatus status,
            @RequestParam(required = false) String managerId,
            @PageableDefault(size = 20, sort = "employeeId") Pageable pageable) {
        return PageResponse.of(employees.search(departmentId, country, status, managerId, pageable),
                EmployeeDtos.EmployeeResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one employee by business ID (EMP-000001)")
    public EmployeeDtos.EmployeeResponse get(@PathVariable String id) {
        return EmployeeDtos.EmployeeResponse.from(employees.require(id));
    }

    @PostMapping
    @Operation(summary = "Create an employee; leave policies and balances are provisioned automatically")
    public ResponseEntity<EmployeeDtos.EmployeeResponse> create(@Valid @RequestBody EmployeeDtos.CreateEmployeeRequest request) {
        Employee created = employees.create(request);
        return ResponseEntity.created(URI.create("/api/v1/employees/" + created.getEmployeeId()))
                .body(EmployeeDtos.EmployeeResponse.from(created));
    }

    @GetMapping("/{id}/leave-balances")
    @Operation(summary = "Leave balances of an employee (available balance is derived from its components)")
    public List<LeaveDtos.LeaveBalanceResponse> leaveBalances(@PathVariable String id) {
        return employees.balancesOf(id).stream().map(LeaveDtos.LeaveBalanceResponse::from).toList();
    }
}
