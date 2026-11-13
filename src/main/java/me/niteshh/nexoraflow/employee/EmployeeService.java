package me.niteshh.nexoraflow.employee;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.common.web.Specs;
import me.niteshh.nexoraflow.configuration.CountryRepository;
import me.niteshh.nexoraflow.costcenter.CostCenterRepository;
import me.niteshh.nexoraflow.department.DepartmentRepository;
import me.niteshh.nexoraflow.leave.LeaveBalance;
import me.niteshh.nexoraflow.leave.LeaveBalanceRepository;
import me.niteshh.nexoraflow.leave.LeaveProvisioningService;

@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final DepartmentRepository departments;
    private final CountryRepository countries;
    private final CostCenterRepository costCenters;
    private final EmployeeCostCenterRepository assignments;
    private final LeaveProvisioningService provisioning;
    private final LeaveBalanceRepository balances;
    private final IdService ids;
    private final AppClock clock;

    public EmployeeService(EmployeeRepository employees, DepartmentRepository departments, CountryRepository countries,
                           CostCenterRepository costCenters, EmployeeCostCenterRepository assignments,
                           LeaveProvisioningService provisioning, LeaveBalanceRepository balances, IdService ids,
                           AppClock clock) {
        this.employees = employees;
        this.departments = departments;
        this.countries = countries;
        this.costCenters = costCenters;
        this.assignments = assignments;
        this.provisioning = provisioning;
        this.balances = balances;
        this.ids = ids;
        this.clock = clock;
    }

    public Employee require(String id) {
        return employees.findById(id).orElseThrow(() -> NexoraException.notFound("Employee", id));
    }

    public Page<Employee> search(String departmentId, String country, EmploymentStatus status, String managerId, Pageable pageable) {
        return employees.findAll(Specs.allOf(Specs.<Employee>eq("departmentId", departmentId), Specs.<Employee>eq("country", country),
                Specs.<Employee>eq("employmentStatus", status), Specs.<Employee>eq("managerId", managerId)), pageable);
    }

    public List<LeaveBalance> balancesOf(String employeeId) {
        require(employeeId);
        return balances.findByEmployeeIdOrderByBalanceYearDescLeavePolicyId(employeeId);
    }

    /** Creates an employee, validates references, assigns the primary cost center and provisions leave policies. */
    @Transactional
    public Employee create(EmployeeDtos.CreateEmployeeRequest req) {
        if (!departments.existsById(req.departmentId())) {
            throw NexoraException.badRequest("Department '" + req.departmentId() + "' does not exist");
        }
        if (!countries.existsById(req.country())) {
            throw NexoraException.badRequest("Country '" + req.country() + "' is not supported");
        }
        if (employees.existsByEmailIgnoreCase(req.email())) {
            throw NexoraException.conflict(null, "An employee with email '" + req.email() + "' already exists");
        }
        if (req.managerId() != null) {
            Employee manager = employees.findById(req.managerId())
                    .orElseThrow(() -> NexoraException.badRequest("Manager '" + req.managerId() + "' does not exist"));
            if (manager.getEmploymentStatus() != EmploymentStatus.ACTIVE) {
                throw NexoraException.badRequest("Manager '" + req.managerId() + "' is not active");
            }
        }
        if (req.costCenterId() != null && !costCenters.existsById(req.costCenterId())) {
            throw NexoraException.badRequest("Cost center '" + req.costCenterId() + "' does not exist");
        }
        Employee e = new Employee();
        String newId = ids.employeeId();
        // self-heal if the counter lags behind explicitly imported IDs (e.g. after loading a seed without counters)
        for (int attempt = 0; attempt < 1000 && employees.existsById(newId); attempt++) {
            newId = ids.employeeId();
        }
        e.setEmployeeId(newId);
        e.setFirstName(req.firstName());
        e.setLastName(req.lastName());
        e.setEmail(req.email());
        e.setPhone(req.phone());
        e.setDepartmentId(req.departmentId());
        e.setDesignation(req.designation());
        e.setJobLevel(req.jobLevel());
        e.setManagerId(req.managerId());
        e.setCountry(req.country());
        e.setLocation(req.location());
        e.setEmploymentType(req.employmentType());
        e.setEmploymentStatus(EmploymentStatus.ACTIVE);
        e.setJoiningDate(req.joiningDate());
        e.setCostCenterId(req.costCenterId());
        employees.saveAndFlush(e);
        if (req.costCenterId() != null) {
            EmployeeCostCenter a = new EmployeeCostCenter();
            a.setAssignmentId(ids.costCenterAssignmentId());
            a.setEmployeeId(e.getEmployeeId());
            a.setCostCenterId(req.costCenterId());
            a.setAssignmentType(AssignmentType.PRIMARY);
            a.setEffectiveFrom(req.joiningDate());
            assignments.save(a);
        }
        int year = Math.max(clock.today().getYear(), req.joiningDate().getYear());
        provisioning.provision(e, year, policy -> BigDecimal.ZERO);
        return e;
    }
}
