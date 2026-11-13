package me.niteshh.nexoraflow.expense;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import me.niteshh.nexoraflow.audit.AuditDtos;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.ReviewCommand;
import me.niteshh.nexoraflow.common.web.IntakeRejectedException;
import me.niteshh.nexoraflow.common.web.PageResponse;
import me.niteshh.nexoraflow.common.web.Specs;
import me.niteshh.nexoraflow.reporting.ExplanationService;

@RestController
@RequestMapping("/api/v1/expense-claims")
@Tag(name = "Expenses", description = "Expense claims: submit, evaluate, cancel, review, explain")
public class ExpenseController {

    private final ExpenseWorkflowService workflow;
    private final ExpenseClaimRepository claims;
    private final ExplanationService explanations;

    public ExpenseController(ExpenseWorkflowService workflow, ExpenseClaimRepository claims, ExplanationService explanations) {
        this.workflow = workflow;
        this.claims = claims;
        this.explanations = explanations;
    }

    @PostMapping
    @Operation(summary = "Submit an expense claim with 1..n line items",
            description = "The claim total is calculated from its lines. Invalid references (unknown cost center, receipt) are "
                    + "preserved in staging and reported; a submission that cannot become a canonical claim is rejected with 422.")
    public ResponseEntity<ExpenseDtos.ExpenseClaimResponse> submit(@Valid @RequestBody ExpenseDtos.SubmitExpenseRequest body,
                                                                    @RequestParam(defaultValue = "false") boolean evaluate) {
        ExpenseSubmissionResult result = workflow.submit(body.toSubmission());
        if (!result.accepted()) {
            throw new IntakeRejectedException(result.submissionId(), result.errors());
        }
        String id = result.claim().getExpenseClaimId();
        if (evaluate) {
            workflow.evaluate(id, body.employeeId()).orThrow();
        }
        return ResponseEntity.created(URI.create("/api/v1/expense-claims/" + id))
                .body(ExpenseDtos.ExpenseClaimResponse.from(workflow.require(id)));
    }

    @GetMapping
    @Operation(summary = "List expense claims (filter by employee, status, cost center, claim date window)")
    public PageResponse<ExpenseDtos.ExpenseClaimResponse> list(
            @RequestParam(required = false) String employeeId,
            @RequestParam(required = false) ExpenseClaimStatus status,
            @RequestParam(required = false) String costCenterId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate claimFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate claimTo,
            @PageableDefault(size = 20, sort = "expenseClaimId") Pageable pageable) {
        var spec = Specs.<ExpenseClaim>allOf(Specs.<ExpenseClaim>eq("employeeId", employeeId),
                Specs.<ExpenseClaim>eq("claimStatus", status), Specs.<ExpenseClaim>eq("costCenterId", costCenterId),
                Specs.<ExpenseClaim, LocalDate>gte("claimDate", claimFrom), Specs.<ExpenseClaim, LocalDate>lte("claimDate", claimTo));
        return PageResponse.of(claims.findAll(spec, pageable), ExpenseDtos.ExpenseClaimResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one expense claim with its line items (EXP-2026-000001)")
    public ExpenseDtos.ExpenseClaimResponse get(@PathVariable String id) {
        return ExpenseDtos.ExpenseClaimResponse.from(workflow.require(id));
    }

    @PostMapping("/{id}/evaluate")
    @Operation(summary = "Evaluate a SUBMITTED claim with the deterministic expense rules",
            description = "Outcome is PENDING_APPROVAL (approval route created), REJECTED, FLAGGED, ON_HOLD or NEEDS_REVIEW.")
    public ExpenseDtos.ExpenseClaimResponse evaluate(@PathVariable String id, @Valid @RequestBody ExpenseDtos.ActorRequest body) {
        return ExpenseDtos.ExpenseClaimResponse.from(workflow.evaluate(id, body.actorEmployeeId()).orThrow());
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an expense claim (requester only, before final approval)")
    public ExpenseDtos.ExpenseClaimResponse cancel(@PathVariable String id, @Valid @RequestBody ExpenseDtos.ActorRequest body) {
        return ExpenseDtos.ExpenseClaimResponse.from(workflow.cancel(id, body.actorEmployeeId(), body.reason()).orThrow());
    }

    @PostMapping("/{id}/review")
    @Operation(summary = "Operations analyst resolves a NEEDS_REVIEW / FLAGGED / ON_HOLD claim (release or reject)")
    public ExpenseDtos.ExpenseClaimResponse review(@PathVariable String id, @Valid @RequestBody ExpenseDtos.ReviewRequest body) {
        return ExpenseDtos.ExpenseClaimResponse.from(workflow.review(id,
                new ReviewCommand(body.actorEmployeeId(), body.action(), body.comment())).orThrow());
    }

    @PatchMapping("/{id}/cost-center")
    @Operation(summary = "Correct a missing / invalid cost center on a held claim (the submitted value is preserved)")
    public ExpenseDtos.ExpenseClaimResponse correctCostCenter(@PathVariable String id,
                                                              @Valid @RequestBody ExpenseDtos.CostCenterCorrectionRequest body) {
        return ExpenseDtos.ExpenseClaimResponse.from(workflow.correctCostCenter(id, body.actorEmployeeId(), body.costCenterId()).orThrow());
    }

    @GetMapping("/{id}/explanation")
    @Operation(summary = "Why was this decision taken? Rules evaluated, passed, failed, approval path, event history")
    public ExplanationService.DecisionExplanation explanation(@PathVariable String id) {
        return explanations.explain(RequestType.EXPENSE, id);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Chronological audit events of the claim")
    public List<AuditDtos.AuditEventResponse> events(@PathVariable String id) {
        workflow.require(id);
        return explanations.history(RequestType.EXPENSE, id);
    }
}
