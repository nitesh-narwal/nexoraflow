package me.niteshh.nexoraflow.costcenter;

import java.time.LocalDate;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import me.niteshh.nexoraflow.budget.BudgetService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;

@RestController
@RequestMapping("/api/v1/cost-centers")
@Tag(name = "Policies and configuration")
public class CostCenterController {

    public record CostCenterResponse(String costCenterId, String costCenterCode, String costCenterName, String departmentId,
                                     String budgetOwnerId, String country, CostCenterStatus status) {
        static CostCenterResponse from(CostCenter c) {
            return new CostCenterResponse(c.getCostCenterId(), c.getCostCenterCode(), c.getCostCenterName(), c.getDepartmentId(),
                    c.getBudgetOwnerId(), c.getCountry(), c.getStatus());
        }
    }

    private final CostCenterRepository costCenters;
    private final BudgetService budgets;
    private final AppClock clock;

    public CostCenterController(CostCenterRepository costCenters, BudgetService budgets, AppClock clock) {
        this.costCenters = costCenters;
        this.budgets = budgets;
        this.clock = clock;
    }

    @GetMapping
    @Operation(summary = "Cost centers (filter by department, country, status)")
    public PageResponse<CostCenterResponse> list(
            @RequestParam(required = false) String departmentId,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) CostCenterStatus status,
            @PageableDefault(size = 50, sort = "costCenterId") Pageable pageable) {
        var spec = Specs.<CostCenter>allOf(Specs.<CostCenter>eq("departmentId", departmentId), Specs.<CostCenter>eq("country", country),
                Specs.<CostCenter>eq("status", status));
        return PageResponse.of(costCenters.findAll(spec, pageable), CostCenterResponse::from);
    }

    @GetMapping("/{id}")
    public CostCenterResponse get(@PathVariable String id) {
        return costCenters.findById(id).map(CostCenterResponse::from).orElseThrow(() -> NexoraException.notFound("Cost center", id));
    }

    @GetMapping("/{id}/budget")
    @Operation(summary = "Budget position of a cost center for a date, derived from source claims (default: today)")
    public BudgetService.BudgetPosition budget(@PathVariable String id,
                                               @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!costCenters.existsById(id)) {
            throw NexoraException.notFound("Cost center", id);
        }
        return budgets.position(id, date == null ? clock.today() : date, null)
                .orElseThrow(() -> NexoraException.notFound("Active budget for cost center", id));
    }
}
