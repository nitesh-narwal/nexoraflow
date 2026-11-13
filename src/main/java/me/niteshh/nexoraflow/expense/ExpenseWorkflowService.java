package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import me.niteshh.nexoraflow.approval.ApprovalRoutingService;
import me.niteshh.nexoraflow.approval.ApprovalService;
import me.niteshh.nexoraflow.approval.RoutePlan;
import me.niteshh.nexoraflow.approval.RoutingContext;
import me.niteshh.nexoraflow.audit.AnomalyService;
import me.niteshh.nexoraflow.audit.AuditCategory;
import me.niteshh.nexoraflow.audit.AuditEventType;
import me.niteshh.nexoraflow.audit.AuditService;
import me.niteshh.nexoraflow.audit.DecisionTraceService;
import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.Decision;
import me.niteshh.nexoraflow.common.EntityType;
import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.common.ReasonCode;
import me.niteshh.nexoraflow.common.RequestType;
import me.niteshh.nexoraflow.common.ReviewAction;
import me.niteshh.nexoraflow.common.ReviewCommand;
import me.niteshh.nexoraflow.common.WorkflowResult;
import me.niteshh.nexoraflow.common.exception.NexoraException;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.common.rules.DecisionAggregator;
import me.niteshh.nexoraflow.common.rules.RuleOutcome;
import me.niteshh.nexoraflow.common.rules.RuleSeverity;
import me.niteshh.nexoraflow.common.rules.RuleVerdict;
import me.niteshh.nexoraflow.configuration.JsonSupport;
import me.niteshh.nexoraflow.costcenter.CostCenterRepository;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmployeeRepository;
import me.niteshh.nexoraflow.employee.EmployeeRules;
import me.niteshh.nexoraflow.intake.IntakeStatus;
import me.niteshh.nexoraflow.intake.SubmissionRecord;
import me.niteshh.nexoraflow.intake.SubmissionRecordRepository;
import me.niteshh.nexoraflow.policy.ExpenseCategory;

/**
 * Orchestrates the expense lifecycle: intake (with preservation of invalid references) -> evaluation ->
 * approval routing -> (approval + reimbursement handled downstream). All decisions come from
 * {@link ExpenseEvaluationEngine}; this class only sequences, persists and audits.
 */
@Service
public class ExpenseWorkflowService {

    private static final Pattern CURRENCY_FORMAT = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern LINE_ID = Pattern.compile("^EXPL-(EXP-\\d{4}-\\d+)-\\d+$");

    private final ExpenseClaimRepository claims;
    private final ReceiptRepository receipts;
    private final EmployeeRepository employees;
    private final CostCenterRepository costCenters;
    private final SubmissionRecordRepository submissions;
    private final ExpenseEvaluationEngine engine;
    private final ReceiptValidator receiptValidator;
    private final ApprovalRoutingService routing;
    private final ApprovalService approvals;
    private final ExpenseStatusUpdater status;
    private final DecisionTraceService traces;
    private final AnomalyService anomalies;
    private final AuditService audit;
    private final IdService ids;
    private final AppClock clock;
    private final JsonSupport json;

    public ExpenseWorkflowService(ExpenseClaimRepository claims, ReceiptRepository receipts, EmployeeRepository employees,
                                  CostCenterRepository costCenters, SubmissionRecordRepository submissions,
                                  ExpenseEvaluationEngine engine, ReceiptValidator receiptValidator,
                                  ApprovalRoutingService routing, ApprovalService approvals, ExpenseStatusUpdater status,
                                  DecisionTraceService traces, AnomalyService anomalies, AuditService audit,
                                  IdService ids, AppClock clock, JsonSupport json) {
        this.claims = claims;
        this.receipts = receipts;
        this.employees = employees;
        this.costCenters = costCenters;
        this.submissions = submissions;
        this.engine = engine;
        this.receiptValidator = receiptValidator;
        this.routing = routing;
        this.approvals = approvals;
        this.status = status;
        this.traces = traces;
        this.anomalies = anomalies;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    public ExpenseClaim require(String id) {
        return claims.findById(id).orElseThrow(() -> NexoraException.notFound("Expense claim", id));
    }

    // ------------------------------------------------------------------ intake

    @Transactional
    public ExpenseSubmissionResult submit(ExpenseSubmission cmd) {
        Instant now = clock.now();
        LocalDate today = clock.today();

        SubmissionRecord staging = new SubmissionRecord();
        staging.setSubmissionId(ids.submissionId());
        staging.setRequestType(RequestType.EXPENSE);
        staging.setReceivedAt(now);
        staging.setSubmittedByRef(cmd.employeeId());
        staging.setRawPayload(json.toJson(cmd));

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Employee employee = cmd.employeeId() == null ? null : employees.findById(cmd.employeeId()).orElse(null);
        if (employee == null) {
            errors.add(ReasonCode.EMPLOYEE_NOT_FOUND + ": " + cmd.employeeId());
        }
        if (cmd.currency() == null || !CURRENCY_FORMAT.matcher(cmd.currency()).matches()) {
            errors.add(ReasonCode.INVALID_CURRENCY + ": malformed claim currency '" + cmd.currency() + "'");
        }
        if (cmd.expensePeriodStart() == null || cmd.expensePeriodEnd() == null) {
            errors.add(ReasonCode.INVALID_DATE_RANGE + ": expense period start and end are required");
        }
        List<ExpenseSubmission.Line> submittedLines = cmd.lines() == null ? List.of() : cmd.lines();
        List<ExpenseCategory> categories = new ArrayList<>();
        for (int i = 0; i < submittedLines.size(); i++) {
            ExpenseSubmission.Line l = submittedLines.get(i);
            int n = i + 1;
            ExpenseCategory category = parseCategory(l.category());
            if (category == null) {
                errors.add(ReasonCode.UNSUPPORTED_CATEGORY + ": '" + l.category() + "' (line " + n + ")");
            }
            categories.add(category);
            if (l.merchantName() == null || l.merchantName().isBlank()) {
                errors.add(ReasonCode.INVALID_REFERENCE + ": merchant name is required (line " + n + ")");
            }
            if (l.transactionDate() == null) {
                errors.add(ReasonCode.INVALID_DATE_RANGE + ": transaction date is required (line " + n + ")");
            }
            if (l.amount() == null) {
                errors.add(ReasonCode.INVALID_AMOUNT + ": amount is required (line " + n + ")");
            }
            if (l.currency() != null && !CURRENCY_FORMAT.matcher(l.currency()).matches()) {
                errors.add(ReasonCode.INVALID_CURRENCY + ": malformed line currency '" + l.currency() + "' (line " + n + ")");
            }
        }
        if (!errors.isEmpty()) {
            staging.setIntakeStatus(IntakeStatus.REJECTED_AT_INTAKE);
            staging.setValidationErrors(json.toJson(errors));
            submissions.save(staging);
            audit.record(AuditCategory.VIOLATION, EntityType.SUBMISSION, staging.getSubmissionId(),
                    AuditEventType.SUBMISSION_REJECTED_AT_INTAKE, null, IntakeStatus.REJECTED_AT_INTAKE.name(),
                    AuditService.SYSTEM_ACTOR, String.join("; ", errors), Map.of("requestType", "EXPENSE"));
            return new ExpenseSubmissionResult(staging.getSubmissionId(), IntakeStatus.REJECTED_AT_INTAKE, null, errors, warnings);
        }

        ExpenseClaim claim = new ExpenseClaim();
        claim.setExpenseClaimId(ids.expenseClaimId(today));
        claim.setEmployeeId(employee.getEmployeeId());
        claim.setClaimDate(today);
        claim.setSubmissionDate(today);
        claim.setSubmittedAt(now);
        claim.setExpensePeriodStart(cmd.expensePeriodStart());
        claim.setExpensePeriodEnd(cmd.expensePeriodEnd());
        claim.setCurrency(cmd.currency());
        claim.setSubmittedCostCenterRef(cmd.costCenterRef());
        String ccRef = cmd.costCenterRef() == null ? "" : cmd.costCenterRef().trim();
        if (!ccRef.isEmpty() && costCenters.existsById(ccRef)) {
            claim.setCostCenterId(ccRef);
        } else if (!ccRef.isEmpty()) {
            warnings.add(ReasonCode.INVALID_COST_CENTER + ": unknown cost center '" + ccRef + "' preserved, not linked");
        } else {
            warnings.add(ReasonCode.INVALID_COST_CENTER + ": cost center missing");
        }
        boolean invertedPeriod = cmd.expensePeriodEnd().isBefore(cmd.expensePeriodStart());
        // the database only accepts an inverted period on a REJECTED record
        claim.setClaimStatus(invertedPeriod ? ExpenseClaimStatus.REJECTED : ExpenseClaimStatus.SUBMITTED);

        List<Receipt> newReceipts = new ArrayList<>();
        for (int i = 0; i < submittedLines.size(); i++) {
            ExpenseSubmission.Line l = submittedLines.get(i);
            ExpenseLine line = new ExpenseLine();
            line.setExpenseLineId(IdService.expenseLineId(claim.getExpenseClaimId(), i + 1));
            line.setLineNumber(i + 1);
            line.setExpenseCategory(categories.get(i));
            line.setMerchantName(l.merchantName().trim());
            line.setTransactionDate(l.transactionDate());
            line.setDescription(l.description());
            line.setAmount(Money.scale(l.amount()));
            line.setCurrency(l.currency() != null ? l.currency() : cmd.currency());
            line.setCostCenterId(claim.getCostCenterId());
            line.setLineStatus(line.getAmount().signum() > 0 ? LineStatus.SUBMITTED : LineStatus.INVALID);
            if (line.getAmount().signum() <= 0) {
                line.setValidationReason(ReasonCode.INVALID_AMOUNT.name());
            }
            if (l.receipt() != null) {
                Receipt receipt = newReceipt(employee, l.receipt(), now);
                newReceipts.add(receipt);
                line.setReceiptId(receipt.getReceiptId());
            } else if (l.receiptRef() != null && !l.receiptRef().isBlank()) {
                if (receipts.existsById(l.receiptRef())) {
                    line.setReceiptId(l.receiptRef());
                } else {
                    line.setSubmittedReceiptRef(l.receiptRef());
                    warnings.add(ReasonCode.INVALID_RECEIPT_REFERENCE + ": receipt '" + l.receiptRef() + "' not found (line " + (i + 1) + ")");
                }
            }
            claim.addLine(line);
        }
        claim.recalculateTotal();
        receipts.saveAll(newReceipts);
        claims.save(claim);

        staging.setIntakeStatus(warnings.isEmpty() && !invertedPeriod ? IntakeStatus.ACCEPTED : IntakeStatus.ACCEPTED_WITH_ERRORS);
        if (!warnings.isEmpty() || invertedPeriod) {
            List<String> all = new ArrayList<>(warnings);
            if (invertedPeriod) {
                all.add(ReasonCode.INVALID_DATE_RANGE + ": expense period end before start");
            }
            staging.setValidationErrors(json.toJson(all));
        }
        staging.setExpenseClaimId(claim.getExpenseClaimId());
        submissions.save(staging);

        audit.record(AuditCategory.WORKFLOW, EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                AuditEventType.REQUEST_SUBMITTED, null, ExpenseClaimStatus.SUBMITTED.name(), employee.getEmployeeId(),
                "Expense claim submitted", Map.of("submissionId", staging.getSubmissionId(), "lines", claim.getLines().size(),
                        "totalAmount", claim.getTotalAmount(), "currency", claim.getCurrency()));

        if (invertedPeriod) {
            Evaluation ev = compute(claim, employee);
            persistEvaluation(claim, ev, AuditService.SYSTEM_ACTOR, ExpenseClaimStatus.REJECTED);
            claim.setDecision(Decision.REJECTED);
            claim.setDecisionReason(ev.aggregate.reasonCodesAsText());
            claim.setEligibilityStatus(EligibilityStatus.INELIGIBLE);
            claims.saveAndFlush(claim);
            audit.record(AuditCategory.WORKFLOW, EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                    AuditEventType.STATUS_CHANGED, ExpenseClaimStatus.SUBMITTED.name(), ExpenseClaimStatus.REJECTED.name(),
                    AuditService.SYSTEM_ACTOR, "Rejected at intake: invalid date range", Map.of());
        }
        return new ExpenseSubmissionResult(staging.getSubmissionId(), staging.getIntakeStatus(), claim, errors, warnings);
    }

    private Receipt newReceipt(Employee employee, ExpenseSubmission.ReceiptUpload upload, Instant now) {
        Receipt r = new Receipt();
        r.setReceiptId(ids.receiptId());
        r.setEmployeeId(employee.getEmployeeId());
        r.setFileName(upload.fileName());
        r.setFileType(upload.fileType() == null ? "UNKNOWN" : upload.fileType().toUpperCase(java.util.Locale.ROOT));
        r.setFileSizeBytes(Math.max(1, upload.fileSizeBytes()));
        r.setUploadedAt(now);
        r.setReceiptHash(upload.receiptHash());
        r.setSourceReference(upload.sourceReference());
        r.setReceiptStatus("VALID");
        r.setReceiptStatus(receiptValidator.validate(r).valid() ? "VALID" : "INVALID");
        return r;
    }

    private static ExpenseCategory parseCategory(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ExpenseCategory.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ evaluation

    @Transactional
    public WorkflowResult<ExpenseClaim> evaluate(String claimId, String actor) {
        ExpenseClaim claim = require(claimId);
        if (claim.getClaimStatus() != ExpenseClaimStatus.SUBMITTED) {
            return invalidTransition(claim, "EVALUATE", actor);
        }
        Employee employee = employees.findById(claim.getEmployeeId()).orElseThrow();
        Evaluation ev = compute(claim, employee);
        ExpenseClaimStatus target = statusFor(ev.aggregate.decision());
        persistEvaluation(claim, ev, actor, target);
        status.transition(claim, target, ev.aggregate.decision(), eligibilityFor(ev.aggregate.decision()),
                ev.aggregate.reasonCodesAsText(), actor, "Evaluation: " + ev.aggregate.decision(),
                Map.of("reasonCodes", ev.aggregate.reasonCodes().toString()));
        applyEffects(claim, ev, actor);
        return WorkflowResult.accepted(claim);
    }

    private void applyEffects(ExpenseClaim claim, Evaluation ev, String actor) {
        if (ev.aggregate.decision() == Decision.PENDING_APPROVAL) {
            routing.createRoute(RequestType.EXPENSE, claim.getExpenseClaimId(), ev.plan, actor);
            return;
        }
        List<ReasonCode> codes = ev.aggregate.reasonCodes();
        if (codes.contains(ReasonCode.SUSPICIOUS_SPLIT_CLAIM) && ev.result.splitFinding().isPresent()) {
            var f = ev.result.splitFinding().get();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("combinedAmountUsd", f.combinedReporting());
            details.put("boundaryUsd", f.boundary());
            details.put("windowDays", f.windowDays());
            details.put("combinedApprovalRoute", ev.combinedPlan == null ? "N/A" : ev.combinedPlan.describe());
            anomalies.record("EXPENSE", "SUSPICIOUS_SPLIT_CLAIM", EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                    f.relatedClaimIds(), "HIGH", ReasonCode.SUSPICIOUS_SPLIT_CLAIM, details);
            flagRelatedClaims(claim, f.relatedClaimIds(), actor, details);
        }
        if (codes.contains(ReasonCode.DUPLICATE_RECEIPT)) {
            anomalies.record("EXPENSE", "DUPLICATE_RECEIPT", EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                    relatedClaimsOf(ev, "EX-13"), "HIGH", ReasonCode.DUPLICATE_RECEIPT, Map.of());
        }
        if (codes.contains(ReasonCode.DUPLICATE_SUBMISSION)) {
            anomalies.record("EXPENSE", "DUPLICATE_SUBMISSION", EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                    relatedClaimsOf(ev, "EX-14"), "MEDIUM", ReasonCode.DUPLICATE_SUBMISSION, Map.of());
        }
        if (codes.contains(ReasonCode.UNAUTHORIZED_COST_CENTER)) {
            anomalies.record("EXPENSE", "UNAUTHORIZED_COST_CENTER", EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                    List.of(), "MEDIUM", ReasonCode.UNAUTHORIZED_COST_CENTER, Map.of("costCenterId", String.valueOf(claim.getCostCenterId())));
        }
    }

    /** Earlier claims of a detected split pattern that are still awaiting approval are held for review too. */
    private void flagRelatedClaims(ExpenseClaim current, List<String> relatedIds, String actor, Map<String, Object> details) {
        for (String relatedId : relatedIds) {
            ExpenseClaim related = claims.findById(relatedId).orElse(null);
            if (related == null || related.getClaimStatus() != ExpenseClaimStatus.PENDING_APPROVAL) {
                continue;
            }
            approvals.cancelOpenSteps(RequestType.EXPENSE, relatedId, actor,
                    "Held: split-claim pattern completed by " + current.getExpenseClaimId());
            status.transition(related, ExpenseClaimStatus.FLAGGED, Decision.FLAGGED, EligibilityStatus.REVIEW_REQUIRED,
                    ReasonCode.SUSPICIOUS_SPLIT_CLAIM.name(), actor,
                    "Flagged retroactively: related claim " + current.getExpenseClaimId() + " completes a split pattern",
                    Map.of("triggeredBy", current.getExpenseClaimId()));
            List<String> others = new ArrayList<>(relatedIds);
            others.remove(relatedId);
            others.add(current.getExpenseClaimId());
            anomalies.record("EXPENSE", "SUSPICIOUS_SPLIT_CLAIM", EntityType.EXPENSE_CLAIM, relatedId, others, "HIGH",
                    ReasonCode.SUSPICIOUS_SPLIT_CLAIM, details);
        }
    }

    private List<String> relatedClaimsOf(Evaluation ev, String ruleId) {
        List<String> related = new ArrayList<>();
        for (RuleOutcome o : ev.outcomes) {
            if (o.failed() && ruleId.equals(o.ruleId()) && o.details().get("failures") instanceof List<?> failures) {
                for (Object f : failures) {
                    if (f instanceof Map<?, ?> m) {
                        Object ref = m.containsKey("alsoUsedOn") ? m.get("alsoUsedOn") : m.get("duplicateOf");
                        if (ref instanceof List<?> lineIds) {
                            lineIds.forEach(id -> {
                                var matcher = LINE_ID.matcher(String.valueOf(id));
                                if (matcher.matches() && !related.contains(matcher.group(1))) {
                                    related.add(matcher.group(1));
                                }
                            });
                        }
                    }
                }
            }
        }
        return related;
    }

    // ------------------------------------------------------------------ cancellation

    @Transactional
    public WorkflowResult<ExpenseClaim> cancel(String claimId, String actor, String reason) {
        ExpenseClaim claim = require(claimId);
        if (!claim.getEmployeeId().equals(actor)) {
            return violation(claim, "CANCEL", actor, ReasonCode.UNAUTHORIZED_APPROVER, "Only the requester can cancel an expense claim");
        }
        if (!ExpenseClaimStatus.LIFECYCLE.canTransition(claim.getClaimStatus(), ExpenseClaimStatus.CANCELLED)) {
            return invalidTransition(claim, "CANCEL", actor);
        }
        approvals.cancelOpenSteps(RequestType.EXPENSE, claimId, actor, "Expense claim cancelled by requester");
        status.transition(claim, ExpenseClaimStatus.CANCELLED, Decision.CANCELLED, EligibilityStatus.INELIGIBLE,
                ReasonCode.CANCELLED_BY_REQUESTER.name(), actor, reason == null ? "Cancelled by requester" : reason, Map.of());
        return WorkflowResult.accepted(claim);
    }

    // ------------------------------------------------------------------ analyst actions

    /** Fixes a missing / invalid cost center on a held claim; the original submitted value is preserved. */
    @Transactional
    public WorkflowResult<ExpenseClaim> correctCostCenter(String claimId, String actor, String costCenterId) {
        ExpenseClaim claim = require(claimId);
        ExpenseClaimStatus s = claim.getClaimStatus();
        if (s != ExpenseClaimStatus.ON_HOLD && s != ExpenseClaimStatus.NEEDS_REVIEW && s != ExpenseClaimStatus.FLAGGED) {
            return invalidTransition(claim, "CORRECT_COST_CENTER", actor);
        }
        if (costCenterId == null || !costCenters.existsById(costCenterId)) {
            return violation(claim, "CORRECT_COST_CENTER", actor, ReasonCode.INVALID_COST_CENTER,
                    "Cost center '" + costCenterId + "' does not exist");
        }
        String previous = claim.getCostCenterId();
        claim.setCostCenterId(costCenterId);
        claim.getLines().forEach(l -> l.setCostCenterId(costCenterId));
        claims.saveAndFlush(claim);
        audit.record(AuditCategory.WORKFLOW, EntityType.EXPENSE_CLAIM, claimId, AuditEventType.COST_CENTER_CORRECTED,
                s.name(), s.name(), actor, "Cost center corrected", Map.of("previous", String.valueOf(previous),
                        "submittedValue", String.valueOf(claim.getSubmittedCostCenterRef()), "corrected", costCenterId));
        return WorkflowResult.accepted(claim);
    }

    @Transactional
    public WorkflowResult<ExpenseClaim> review(String claimId, ReviewCommand cmd) {
        ExpenseClaim claim = require(claimId);
        String actor = cmd.actorEmployeeId();
        ExpenseClaimStatus from = claim.getClaimStatus();
        if (from != ExpenseClaimStatus.NEEDS_REVIEW && from != ExpenseClaimStatus.FLAGGED && from != ExpenseClaimStatus.ON_HOLD) {
            return invalidTransition(claim, "REVIEW", actor);
        }
        if (cmd.action() == ReviewAction.REJECT) {
            status.transition(claim, ExpenseClaimStatus.REJECTED, Decision.REJECTED, EligibilityStatus.INELIGIBLE,
                    ReasonCode.REJECTED_BY_ANALYST.name(), actor, cmd.comment(), Map.of());
            audit.record(AuditCategory.WORKFLOW, EntityType.EXPENSE_CLAIM, claimId, AuditEventType.REVIEW_RESOLVED,
                    from.name(), ExpenseClaimStatus.REJECTED.name(), actor, ReasonCode.REJECTED_BY_ANALYST.name(),
                    Map.of("comment", String.valueOf(cmd.comment())));
            return WorkflowResult.accepted(claim);
        }
        Employee employee = employees.findById(claim.getEmployeeId()).orElseThrow();
        Evaluation ev = compute(claim, employee);
        if (ev.aggregate.decision() == Decision.REJECTED || ev.aggregate.decision() == Decision.ON_HOLD) {
            return violation(claim, "REVIEW_RELEASE", actor, ReasonCode.INVALID_STATE_TRANSITION,
                    "Cannot release: blocking conditions remain " + ev.aggregate.reasonCodes());
        }
        BigDecimal routeAmount = ev.result.totalReporting();
        if (ev.result.splitFinding().isPresent()) {
            routeAmount = routeAmount.max(ev.result.splitFinding().get().combinedReporting());
        }
        RoutePlan plan = routing.plan(new RoutingContext(RequestType.EXPENSE, employee, routeAmount, ev.result.categories(), clock.today()));
        if (!plan.resolvable()) {
            return violation(claim, "REVIEW_RELEASE", actor, ReasonCode.NO_VALID_APPROVER, plan.failureMessage());
        }
        persistEvaluation(claim, new Evaluation(ev.result, ev.outcomes, ev.aggregate, plan, ev.combinedPlan), actor,
                ExpenseClaimStatus.PENDING_APPROVAL);
        status.transition(claim, ExpenseClaimStatus.PENDING_APPROVAL, Decision.PENDING_APPROVAL, EligibilityStatus.ELIGIBLE,
                ReasonCode.RELEASED_BY_ANALYST.name(), actor, cmd.comment(),
                Map.of("waivedReasonCodes", ev.aggregate.reasonCodes().toString(), "approvalPath", plan.describe()));
        audit.record(AuditCategory.WORKFLOW, EntityType.EXPENSE_CLAIM, claimId, AuditEventType.REVIEW_RESOLVED,
                from.name(), ExpenseClaimStatus.PENDING_APPROVAL.name(), actor, ReasonCode.RELEASED_BY_ANALYST.name(),
                Map.of("comment", String.valueOf(cmd.comment()), "waivedReasonCodes", ev.aggregate.reasonCodes().toString()));
        routing.createRoute(RequestType.EXPENSE, claimId, plan, actor);
        return WorkflowResult.accepted(claim);
    }

    // ------------------------------------------------------------------ evaluation internals

    private record Evaluation(ExpenseEvaluationResult result, List<RuleOutcome> outcomes,
                              DecisionAggregator.Aggregate aggregate, RoutePlan plan, RoutePlan combinedPlan) {
    }

    private Evaluation compute(ExpenseClaim claim, Employee employee) {
        ExpenseEvaluationResult result = engine.evaluate(claim, employee);
        List<RuleOutcome> outcomes = new ArrayList<>(result.outcomes());
        DecisionAggregator.Aggregate aggregate = DecisionAggregator.aggregate(outcomes);
        RoutePlan plan = RoutePlan.notApplicable();
        RoutePlan combined = null;
        if (result.totalReporting() != null) {
            RoutingContext ctx = new RoutingContext(RequestType.EXPENSE, employee, result.totalReporting(),
                    result.categories(), clock.today());
            if (aggregate.decision() == Decision.PENDING_APPROVAL) {
                plan = routing.plan(ctx);
                outcomes.add(plan.resolvable()
                        ? RuleOutcome.pass("EX-19", "APPROVER_RESOLVABLE", "Approval route: " + plan.describe())
                        : RuleOutcome.fail("EX-19", "APPROVER_RESOLVABLE", RuleSeverity.REVIEW, ReasonCode.NO_VALID_APPROVER,
                                plan.failureMessage()));
                aggregate = DecisionAggregator.aggregate(outcomes);
            } else if (aggregate.decision() != Decision.REJECTED) {
                plan = routing.plan(ctx);
            }
            if (result.splitFinding().isPresent()) {
                combined = routing.plan(new RoutingContext(RequestType.EXPENSE, employee,
                        result.splitFinding().get().combinedReporting(), result.categories(), clock.today()));
            }
        }
        return new Evaluation(result, outcomes, aggregate, plan, combined);
    }

    private void persistEvaluation(ExpenseClaim claim, Evaluation ev, String actor, ExpenseClaimStatus target) {
        for (LineResult lr : ev.result.lines()) {
            ExpenseLine line = claim.getLines().get(lr.lineNumber() - 1);
            line.setLineStatus(lr.status());
            line.setExpensePolicyId(lr.policyId());
            line.setAmountReporting(lr.amountReporting());
            String reason = lr.reasons().isEmpty() ? null
                    : lr.reasons().stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(null);
            line.setValidationReason(reason);
        }
        claims.saveAndFlush(claim);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("requestId", claim.getExpenseClaimId());
        trace.put("requestType", "EXPENSE");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("employeeId", claim.getEmployeeId());
        input.put("claimDate", String.valueOf(claim.getClaimDate()));
        input.put("expensePeriod", claim.getExpensePeriodStart() + " to " + claim.getExpensePeriodEnd());
        input.put("currency", claim.getCurrency());
        input.put("totalAmount", claim.getTotalAmount());
        input.put("totalAmountUsd", ev.result.totalReporting());
        input.put("costCenterId", claim.getCostCenterId());
        input.put("submittedCostCenterRef", claim.getSubmittedCostCenterRef());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ExpenseLine l : claim.getLines()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineNumber", l.getLineNumber());
            m.put("category", l.getExpenseCategory().name());
            m.put("merchant", l.getMerchantName());
            m.put("transactionDate", String.valueOf(l.getTransactionDate()));
            m.put("amount", l.getAmount());
            m.put("receiptId", l.getReceiptId());
            m.put("status", l.getLineStatus().name());
            m.put("policyId", l.getExpensePolicyId());
            m.put("validationReason", l.getValidationReason());
            lines.add(m);
        }
        input.put("lines", lines);
        trace.put("input", input);
        trace.put("applicablePolicies", ev.result.lines().stream().map(LineResult::policyId).filter(p -> p != null).distinct().toList());
        trace.put("rulesEvaluated", ev.outcomes);
        trace.put("rulesPassed", ev.outcomes.stream().filter(o -> o.verdict() == RuleVerdict.PASS).map(RuleOutcome::ruleId).toList());
        trace.put("rulesFailed", ev.outcomes.stream().filter(RuleOutcome::failed).map(RuleOutcome::ruleId).toList());
        trace.put("rulesSkipped", ev.outcomes.stream().filter(o -> o.verdict() == RuleVerdict.SKIPPED).map(RuleOutcome::ruleId).toList());
        trace.put("decision", ev.aggregate.decision().name());
        trace.put("reasonCodes", ev.aggregate.reasonCodes().stream().map(Enum::name).toList());
        trace.put("approvalPath", ev.plan.resolvable() ? ev.plan.steps() : List.of());
        trace.put("approvalPathText", ev.plan.describe());
        if (ev.combinedPlan != null) {
            trace.put("combinedClaimsApprovalPathText", ev.combinedPlan.describe());
        }
        String policyRef = String.join(",", ev.result.lines().stream().map(LineResult::policyId).filter(p -> p != null).distinct().toList());
        traces.record(RequestType.EXPENSE, claim.getExpenseClaimId(), ev.aggregate.decision().name(),
                ev.aggregate.reasonCodesAsText(), policyRef, trace);

        audit.record(AuditCategory.DECISION, EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                AuditEventType.REQUEST_EVALUATED, claim.getClaimStatus().name(), target.name(), actor,
                ev.aggregate.reasonCodesAsText().isEmpty() ? "All rules passed" : ev.aggregate.reasonCodesAsText(),
                Map.of("decision", ev.aggregate.decision().name(), "approvalPath", ev.plan.describe(),
                        "totalAmountUsd", String.valueOf(ev.result.totalReporting())));
    }

    private static ExpenseClaimStatus statusFor(Decision decision) {
        return switch (decision) {
            case PENDING_APPROVAL -> ExpenseClaimStatus.PENDING_APPROVAL;
            case REJECTED -> ExpenseClaimStatus.REJECTED;
            case FLAGGED -> ExpenseClaimStatus.FLAGGED;
            case NEEDS_REVIEW -> ExpenseClaimStatus.NEEDS_REVIEW;
            case ON_HOLD -> ExpenseClaimStatus.ON_HOLD;
            case APPROVED, CANCELLED -> throw new IllegalStateException("Evaluation cannot produce " + decision);
        };
    }

    private static EligibilityStatus eligibilityFor(Decision decision) {
        return switch (decision) {
            case PENDING_APPROVAL -> EligibilityStatus.ELIGIBLE;
            case REJECTED -> EligibilityStatus.INELIGIBLE;
            default -> EligibilityStatus.REVIEW_REQUIRED;
        };
    }

    private WorkflowResult<ExpenseClaim> invalidTransition(ExpenseClaim claim, String action, String actor) {
        return violation(claim, action, actor, ReasonCode.INVALID_STATE_TRANSITION,
                "Action " + action + " is not allowed while the claim is " + claim.getClaimStatus());
    }

    private WorkflowResult<ExpenseClaim> violation(ExpenseClaim claim, String action, String actor, ReasonCode code, String message) {
        audit.record(AuditCategory.VIOLATION, EntityType.EXPENSE_CLAIM, claim.getExpenseClaimId(),
                AuditEventType.INVALID_TRANSITION_REJECTED, claim.getClaimStatus().name(), claim.getClaimStatus().name(),
                actor, code.name(), Map.of("attemptedAction", action, "message", message));
        return WorkflowResult.rejected(code, message);
    }

    /** Distinct categories of a claim (used by callers that need the routing context). */
    public static Set<ExpenseCategory> categoriesOf(ExpenseClaim claim) {
        return ExpenseEvaluationEngine.categoriesOf(claim.getLines());
    }
}
