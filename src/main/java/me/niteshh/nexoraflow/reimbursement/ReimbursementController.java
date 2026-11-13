package me.niteshh.nexoraflow.reimbursement;

import java.net.URI;

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
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;

@RestController
@RequestMapping("/api/v1/reimbursements")
@Tag(name = "Reimbursements", description = "Reimbursements are created only for eligible, finally approved claims - at most once per claim")
public class ReimbursementController {

    private final ReimbursementService service;
    private final ReimbursementRepository repository;

    public ReimbursementController(ReimbursementService service, ReimbursementRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List reimbursements (filter by employee and status)")
    public PageResponse<ReimbursementDtos.ReimbursementResponse> list(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) ReimbursementStatus status,
            @PageableDefault(size = 20, sort = "reimbursementId") Pageable pageable) {
        var spec = Specs.<Reimbursement>allOf(Specs.<Reimbursement>eq("employeeId", employeeId),
                Specs.<Reimbursement>eq("reimbursementStatus", status));
        return PageResponse.of(repository.findAll(spec, pageable), ReimbursementDtos.ReimbursementResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One reimbursement (RMB-2026-000001)")
    public ReimbursementDtos.ReimbursementResponse get(@PathVariable String id) {
        return ReimbursementDtos.ReimbursementResponse.from(service.require(id));
    }

    @PostMapping
    @Operation(summary = "Request reimbursement of an approved claim (idempotent)",
            description = "201 when created. 409 DUPLICATE_REIMBURSEMENT when one already exists (the attempt is recorded). "
                    + "422 CLAIM_NOT_REIMBURSABLE when the claim is not eligible and finally approved.")
    public ResponseEntity<ReimbursementDtos.ReimbursementResponse> request(@Valid @RequestBody ReimbursementDtos.CreateReimbursementRequest body) {
        ReimbursementResult result = service.requestReimbursement(body.expenseClaimId(), body.requestedBy());
        return switch (result.outcome()) {
            case CREATED -> ResponseEntity.created(URI.create("/api/v1/reimbursements/" + result.reimbursement().getReimbursementId()))
                    .body(ReimbursementDtos.ReimbursementResponse.from(result.reimbursement()));
            case DUPLICATE_REJECTED -> throw NexoraException.conflict(result.reasonCode(), result.message());
            case NOT_ELIGIBLE_REJECTED -> throw NexoraException.unprocessable(result.reasonCode(), result.message());
        };
    }

    @PostMapping("/{id}/process")
    @Operation(summary = "Mark a reimbursement as paid (simulates the payroll run)")
    public ReimbursementDtos.ReimbursementResponse process(@PathVariable String id, @Valid @RequestBody ReimbursementDtos.ProcessRequest body) {
        return ReimbursementDtos.ReimbursementResponse.from(service.markPaid(id, body.actor()).orThrow());
    }
}
