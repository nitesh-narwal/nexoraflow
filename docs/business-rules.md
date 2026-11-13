# Business rules (version BR-1.0.0)

Every decision in NexoraFlow is **deterministic, explainable and testable**: the same input and the same configuration always produce the same decision, and every decision can be explained rule by rule (`GET /api/v1/leave-requests/{id}/explanation`, `GET /api/v1/expense-claims/{id}/explanation`). There is no machine learning and no randomness in the business engines; randomness exists only in the synthetic-data generator and only through a seeded generator.

The rule version `BR-1.0.0` is stored on every decision trace. It is part of the reproducibility contract (see [assumptions.md](assumptions.md)).

Where a number in this document is a *default* (for example the approval thresholds), it is data in a table (`approval_rule`, `leave_policy`, `expense_policy`, `budget`) and can be changed without touching code.

## 1. How a decision is made

Each engine evaluates an ordered list of **rules**. Every rule returns one outcome:

| Outcome | Meaning |
|---|---|
| `PASS` | the condition holds |
| `FAIL` | the condition is violated; the outcome carries a **severity**, a **reason code** and structured details (amounts, limits, related records) |
| `SKIPPED` | the rule cannot be evaluated because a prerequisite failed (for example the balance rule when the date range is invalid); skipped rules are shown, never silently dropped |

The **severity** of the strongest failed rule decides what happens to the request. Precedence is fixed:

| Severity | Decision | Leave status | Expense status | Typical meaning |
|---|---|---|---|---|
| `HARD` | `REJECTED` | `REJECTED` | `REJECTED` | the request is invalid |
| `HOLD` | `ON_HOLD` | - (no hold rule exists for leave) | `ON_HOLD` | a blocking condition (cost center, budget) must be resolved first |
| `FLAG` | `FLAGGED` | `FLAGGED` | `FLAGGED` | suspicious; an operations analyst must look at it |
| `REVIEW` | `NEEDS_REVIEW` | `NEEDS_REVIEW` | `NEEDS_REVIEW` | plausible, but a human check is needed (missing documentation, no approver) |
| none | `PENDING_APPROVAL` | `PENDING_APPROVAL` | `PENDING_APPROVAL` | proceeds to the approval workflow |

`HARD > HOLD > FLAG > REVIEW`. All failed rules are reported (not only the strongest) and all their reason codes are stored on the request (`decision_reason`) and in the decision trace.

Business violations are returned as `WorkflowResult` values rather than thrown as exceptions, so that the audit event of the rejected attempt is committed together with the unchanged state. The REST layer maps them to HTTP: `403` for self-approval / unauthorised / inactive approver, `409` for invalid state transition, wrong step order, completed step, duplicate reimbursement, `422` for everything else.

## 2. Leave rules

Implemented by `LeaveEvaluationEngine` (pure, no database access) plus `LeaveWorkflowService` (LV-11). Evaluation happens when a `SUBMITTED` request is evaluated (`POST /api/v1/leave-requests/{id}/evaluate`).

| ID | Rule | Fails when | Severity | Reason code |
|---|---|---|---|---|
| LV-01 | `EMPLOYEE_ACTIVE` | employee status is not `ACTIVE`, or the employee was terminated before the end of the leave | HARD | `INACTIVE_EMPLOYEE` |
| LV-02 | `POLICY_EFFECTIVE` | the policy is not `ACTIVE` or not effective for the whole requested period | HARD | `POLICY_NOT_EFFECTIVE` |
| LV-03 | `EMPLOYEE_ELIGIBLE` | the employment type is not eligible, the policy is country-specific for another country, or the employee has no effective policy assignment covering the period | HARD | `EMPLOYEE_NOT_ELIGIBLE` |
| LV-04 | `VALID_DATE_RANGE` | a date is missing, end is before start, or the request crosses a calendar year | HARD | `INVALID_DATE_RANGE` |
| LV-05 | `BUSINESS_DAYS_CALCULATED` | the range contains no working day (only weekends and public holidays) | HARD | `HOLIDAY_OR_NON_WORKING_DAY_ONLY` |
| LV-06 | `MINIMUM_NOTICE` | days between submission date and start date < the policy's minimum notice | HARD | `INSUFFICIENT_NOTICE` |
| LV-07 | `MAXIMUM_CONSECUTIVE_DAYS` | business days > the policy's maximum consecutive days | HARD | `POLICY_LIMIT_EXCEEDED` |
| LV-08 | `SUFFICIENT_BALANCE` | available balance < requested business days (or no balance exists for employee, policy and year) | HARD | `INSUFFICIENT_BALANCE` |
| LV-09 | `NO_APPROVED_OVERLAP` | the dates overlap another **approved** leave request of the same employee | FLAG | `OVERLAPPING_APPROVED_LEAVE` |
| LV-10 | `DOCUMENTATION_PROVIDED` | the policy requires documentation above N business days, the request is longer, and no supporting document reference was given | REVIEW | `DOCUMENTATION_MISSING` |
| LV-11 | `APPROVER_RESOLVABLE` | no active, authorised approver can be determined for the approval route | REVIEW | `NO_VALID_APPROVER` |

Business days are counted by `BusinessDayCalculator`: Saturdays, Sundays and the **public holidays of the employee's country** (calendar `HC-<country>-<year>`) are excluded. It is never "end - start + 1".

LV-05 .. LV-10 are `SKIPPED` when LV-04 fails (no valid range to evaluate). LV-08 is `SKIPPED` when LV-03 fails.

Overlap (LV-09) is checked at evaluation **and again at final approval**: if two overlapping requests are approved in a race, the second approval is blocked (`FINAL_APPROVAL_BLOCKED` audit event) and the request is flagged instead of silently double-booking the employee.

### Leave balance policy

`available = opening + accrued + carried forward - used - pending` (a database generated column). A balance only ever changes by posting an immutable **ledger transaction** (`leave_balance_transaction`); the validation module proves that every balance reconciles with its ledger (BI-02).

| Event | Ledger transaction | Effect |
|---|---|---|
| balance opened (`ANNUAL_UPFRONT`) | `OPENING_GRANT` | `opening += entitlement` (pro-rated by months remaining for mid-year joiners) |
| month accrual (`MONTHLY`) | `ACCRUAL` (one per balance and month - unique index) | `accrued += instalment`; cumulative rounding so the twelve instalments sum to exactly the annual entitlement |
| new year | `CARRY_FORWARD` | `carried forward += min(unused, policy carry-forward limit)` |
| request enters approval | `PENDING_RESERVE` | `pending += days` (concurrent requests cannot over-commit the balance) |
| final approval | `CONSUME` (at most once per request - unique index) | `pending -= days`, `used += days`; *available does not change at this moment*, so leave is never deducted twice |
| rejection, cancellation, flagging, release | `PENDING_RELEASE` | `pending -= days` |
| cancellation of an **approved** request | `RESTORE` (at most once per request) | `used -= days` |

A request that is rejected, cancelled or never approved never consumes balance (BI-04); an approved request consumes it exactly once (BI-03).

### Leave lifecycle

`SUBMITTED -> PENDING_APPROVAL | NEEDS_REVIEW | FLAGGED | REJECTED | CANCELLED`;
`NEEDS_REVIEW | FLAGGED -> PENDING_APPROVAL | REJECTED | CANCELLED` (analyst review);
`PENDING_APPROVAL -> APPROVED | REJECTED | FLAGGED | CANCELLED`;
`APPROVED -> CANCELLED` (restores the balance); `REJECTED` and `CANCELLED` are terminal.
Every transition is checked against an explicit state machine (`StateMachine`); an invalid transition is rejected, recorded as `INVALID_TRANSITION_REJECTED` and changes nothing. See [workflow-diagrams.md](workflow-diagrams.md).

## 3. Expense rules

Implemented by `ExpenseEvaluationEngine`. The **claim** is the unit of evaluation and approval; each **line** is also evaluated individually and receives `VALID`, `FLAGGED` or `INVALID` with its own reason codes. The claim total is the sum of the positive line amounts. Policies, other claims, receipts, budgets and approval rules are read from the database; ground-truth labels are never read.

| ID | Rule | Fails when | Severity | Reason code |
|---|---|---|---|---|
| EX-01 | `EMPLOYEE_ELIGIBLE_TO_CLAIM` | the employee's status is `INACTIVE` (terminated employees may still claim expenses from their employment period - see EX-07) | HARD | `INACTIVE_EMPLOYEE` |
| EX-02 | `CLAIM_DATES_VALID` | the expense period is missing or inverted | HARD | `INVALID_DATE_RANGE` |
| EX-03 | `LINE_ITEM_COUNT` | no line items, or more than the configured maximum (default 10) | HARD | `NO_LINE_ITEMS` / `TOO_MANY_LINE_ITEMS` |
| EX-04 | `CURRENCY_VALID` | the currency is unsupported, differs from the employee's country currency, or a line currency differs from the claim currency | HARD | `INVALID_CURRENCY` |
| EX-05 | `AMOUNT_VALID` | a line amount is missing, zero or negative | HARD | `INVALID_AMOUNT` |
| EX-06 | `TRANSACTION_DATES_VALID` | a transaction date is missing, outside the expense period, or after the submission date | HARD | `INVALID_DATE_RANGE` |
| EX-07 | `EMPLOYMENT_PERIOD` | a transaction is dated before joining or after termination | HARD | `OUTSIDE_EMPLOYMENT_PERIOD` |
| EX-08 | `POLICY_APPLICABLE` | no policy exists for the category, country and employee type (`NO_APPLICABLE_POLICY`), or one exists but not on the transaction date (`POLICY_NOT_EFFECTIVE`) | HARD | `NO_APPLICABLE_POLICY` / `POLICY_NOT_EFFECTIVE` |
| EX-09 | `PER_TRANSACTION_LIMIT` | a line amount exceeds the policy's per-transaction maximum | HARD | `POLICY_LIMIT_EXCEEDED` |
| EX-10 | `DAILY_LIMIT` | the employee's total for this category on one day (this claim + already committed claims) exceeds the daily maximum | HARD | `POLICY_LIMIT_EXCEEDED` |
| EX-11 | `MONTHLY_LIMIT` | the employee's total for this category in one month (this claim + committed claims) exceeds the monthly maximum | HARD | `POLICY_LIMIT_EXCEEDED` |
| EX-12 | `RECEIPT_REQUIREMENT` | the policy requires a receipt above a threshold and the line has none (`RECEIPT_MISSING`), references an unknown receipt (`INVALID_RECEIPT_REFERENCE`) or the receipt has an unsupported type, size or status (`RECEIPT_INVALID`) | FLAG | `RECEIPT_MISSING` / `INVALID_RECEIPT_REFERENCE` / `RECEIPT_INVALID` |
| EX-13 | `NO_DUPLICATE_RECEIPT` | the same receipt hash is attached to a line of another live claim | FLAG | `DUPLICATE_RECEIPT` |
| EX-14 | `NO_DUPLICATE_SUBMISSION` | the same employee already submitted a line with the same merchant, transaction date and amount on another live claim | FLAG | `DUPLICATE_SUBMISSION` |
| EX-15 | `COST_CENTER_VALID` | the cost center is missing, unknown or not `ACTIVE` | HOLD | `INVALID_COST_CENTER` |
| EX-16 | `COST_CENTER_AUTHORIZED` | the employee has no effective assignment to the cost center on the claim date | FLAG | `UNAUTHORIZED_COST_CENTER` |
| EX-17 | `BUDGET_AVAILABLE` | the claim exceeds the remaining budget of the cost center for the claim month (or no active budget exists) | HOLD | `INSUFFICIENT_BUDGET` |
| EX-18 | `NO_SPLIT_CLAIM` | related claims individually stay at or below an approval boundary but together exceed it (see below) | FLAG | `SUSPICIOUS_SPLIT_CLAIM` |
| EX-19 | `APPROVER_RESOLVABLE` | no active, authorised approver can be determined for the approval route | REVIEW | `NO_VALID_APPROVER` |

"Live claim" means any claim that is not `REJECTED` or `CANCELLED`. For the daily and monthly limits only claims in `PENDING_APPROVAL` or `APPROVED` count as committed spend. Limits are compared in the claim currency, which must equal the policy currency (the employee's country currency, EX-04).

EX-09 .. EX-11 are `SKIPPED` when the currency is invalid; EX-16 .. EX-18 are `SKIPPED` when the cost center or currency is invalid.

### Receipts

Receipt **metadata** is validated (file type `PDF/JPG/JPEG/PNG/HEIC`, size 1 byte .. 10 MiB, status `VALID`); there is no document content and no OCR. The SHA-256 hash identifies a reused receipt.

### Budget

`remaining = allocated - approved spend - pending commitments` and it is **always derived from source claims** (`BudgetService`); no spend counter is stored, so it cannot drift. Claims are charged to the budget period containing their claim date. Pending commitments (claims awaiting approval) count only when the budget has `commit_pending = true`. A claim in another currency is converted at the fixed documented rate.

### Split-claim detection (EX-18)

`SplitClaimDetector` looks for several claims that together look like one purchase made small enough to stay under an approval threshold.

A finding needs **all** of: same employee, same cost center, same category, same normalised merchant, transaction dates **chained** within a window of N days (default 3; a chain is followed transitively: claims on day 0, day 1 and day 4 form one chain although the first and the last are four days apart), at least two distinct claims, **each individually at or below an approval boundary B**, and together **above B**. The boundaries are read from the active expense approval rules (500, 2000, 5000 USD by default) - nothing is hard-coded. Description similarity is reported as supporting evidence but is not required (evasion often changes the wording).

When a finding exists, every involved claim is flagged, the combined amount and the approval route the combined amount *would* have required are recorded (`detected_anomaly.details`), and earlier claims of the pattern that are still awaiting approval are retroactively flagged too (their open approval steps are cancelled, `STATUS_CHANGED` is audited). Claims that were already approved keep their status and are linked through the anomaly record.

Documented limitations: different merchant spellings, or different merchants for one purchase, are not linked; a legitimate series of purchases at one merchant can be flagged (the outcome is `FLAGGED` for review, never an automatic rejection); amounts are compared in the reporting currency.

### Expense lifecycle

`SUBMITTED -> PENDING_APPROVAL | NEEDS_REVIEW | FLAGGED | ON_HOLD | REJECTED | CANCELLED`;
`NEEDS_REVIEW | FLAGGED | ON_HOLD -> PENDING_APPROVAL | REJECTED | CANCELLED`;
`PENDING_APPROVAL -> APPROVED | REJECTED | FLAGGED | ON_HOLD | CANCELLED`;
`APPROVED`, `REJECTED`, `CANCELLED` are terminal. Reimbursement state is tracked separately on the reimbursement.

An `ON_HOLD` claim whose cost center is missing can be corrected with `PATCH /api/v1/expense-claims/{id}/cost-center`: the originally submitted value is preserved (`submitted_cost_center_ref`), the claim is re-evaluated (`evaluation_number` 2 in the decision trace) and the correction is audited (`COST_CENTER_CORRECTED`).

## 4. Approval rules

### Rule selection

Routing thresholds are **rows in `approval_rule`**, not code. For a request, `ApprovalRuleResolver` selects, **per approval level**, the single applicable rule. A rule applies when:

- `rule_status = ACTIVE` and the request date lies within `effective_from .. effective_to`;
- its optional scope filters (country, department, expense category) match;
- `minimum_amount < amount <= maximum_amount` (lower bound exclusive, upper bound inclusive; `NULL` maximum = unbounded).

The amount is the number of **business days** for leave and the **USD-equivalent claim total** for expenses (fixed, documented conversion rates). If several rules of one level apply, the lowest `priority` number wins, then the most specific rule (most scope filters), then the lowest rule ID. Levels run in ascending order.

Seeded configuration (`BR-1.0.0`):

| Workflow | Amount | Level 1 | Level 2 |
|---|---|---|---|
| Expense | <= 500 USD | direct manager | - |
| Expense | 500 - 2,000 USD | manager (job level <= 3) | - |
| Expense | 2,000 - 5,000 USD | manager | Finance Manager |
| Expense | > 5,000 USD | manager | VP Finance |
| Expense, category `CLIENT_ENTERTAINMENT` | <= 2,000 USD | (as above) | additional Finance Manager review (compliance, priority 10) |
| Leave | <= 10 business days | direct manager | - |
| Leave | > 10 business days | direct manager | department head (job level <= 2) |

Two further rows exist to prove they are never applied: an `INACTIVE` "decommissioned CFO review" rule (`AR-EX-008`) and a rule that expired on 2025-12-31 (`AR-EX-009`).

### Approver resolution

`ApproverResolver` locates the person for each role:

| Role | Resolved as |
|---|---|
| `DIRECT_MANAGER` | the nearest **available** manager in the requester's reporting line |
| `MANAGER` | the nearest available manager with job level <= 3 |
| `DEPARTMENT_HEAD` | the nearest available manager with job level <= 2 |
| `FINANCE_REVIEWER` | an active employee with designation "Finance Manager" (prefers the requester's country, then the lowest ID) |
| `FINANCE_HEAD` | an active employee with designation "VP Finance" (same preference) |

*Available* means active on the date. If the natural approver is unavailable (inactive or terminated) the resolver **escalates up the reporting line** and records an `APPROVER_ESCALATED_UNAVAILABLE` audit event; if nobody qualifies the route is unresolvable (LV-11 / EX-19, reason `NO_VALID_APPROVER`). A requester is never their own approver.

If the same person fills two levels of a route, the later step is created as `SKIPPED` ("same approver as level N; no second approval required"): nobody approves twice, and nobody is skipped silently - the skipped step stays visible in the audit trail.

### Approval restrictions

Enforced by `ApprovalService` **and** (for the structural ones) by the `approval_step` trigger:

| ID | Restriction | Violation produces | HTTP |
|---|---|---|---|
| AP-01 | a requester cannot approve their own request | `SELF_APPROVAL_NOT_ALLOWED` | 403 |
| AP-02 | only the assigned approver may act on a step | `UNAUTHORIZED_APPROVER` | 403 |
| AP-03 | the approver must be an active employee | `APPROVER_INACTIVE` | 403 |
| AP-04 | steps must be completed in the configured order | `INVALID_STEP_ORDER` | 409 |
| AP-05 | a completed step cannot be overwritten | `STEP_ALREADY_COMPLETED` | 409 |
| AP-06 | after a rejection no further step runs; the remaining steps are `CANCELLED` | - | - |
| AP-07 | a request that is no longer `PENDING_APPROVAL` cannot be approved | `INVALID_STATE_TRANSITION` | 409 |

Every rejected attempt is written to the audit trail (category `VIOLATION`, filed under the request it concerns) and leaves the request unchanged: authorisation failures (self-approval, wrong or inactive approver) as `APPROVAL_ATTEMPT_REJECTED`, structural violations (wrong order, completed step, request not awaiting approval) as `INVALID_TRANSITION_REJECTED`. An approver may **escalate** a pending step to their own manager (`POST /api/v1/approvals/{id}/escalate`): the old step becomes `ESCALATED` and a replacement step is created.

### Effects of the final approval

- **Leave:** the reserved days are consumed exactly once, after re-checking for an overlap with leave approved in the meantime.
- **Expense:** the budget is **re-checked** (other claims may have consumed it since the evaluation); if it no longer covers the claim the final approval is blocked (`FINAL_APPROVAL_BLOCKED`, reason `INSUFFICIENT_BUDGET`) and the claim is put `ON_HOLD`. Otherwise the claim becomes `APPROVED` and `ELIGIBLE` and **the reimbursement is requested automatically** in the same transaction (section 5).

## 5. Reimbursement rules

The reimbursement of a claim is requested **automatically at final approval** (`ExpenseApprovalHandler` calls `ReimbursementService.requestReimbursement(claim)` after the claim became `APPROVED` and `ELIGIBLE`); it is created with status `PENDING_PAYMENT`. `POST /reimbursements` calls the same method and is therefore **idempotent** and safe to call any number of times - in the normal case it answers `409 DUPLICATE_REIMBURSEMENT`:

| ID | Rule | Result |
|---|---|---|
| RB-01 | only a claim that is `APPROVED`, `ELIGIBLE` and whose approval steps are all completed (none `WAITING`, `PENDING` or `REJECTED`, at least one `APPROVED`) can be reimbursed | otherwise `NOT_ELIGIBLE_REJECTED` with reason `CLAIM_NOT_REIMBURSABLE` |
| RB-02 | at most one reimbursement per claim | a second request returns the existing reimbursement as `DUPLICATE_REJECTED` with reason `DUPLICATE_REIMBURSEMENT` (HTTP 409) |
| RB-03 | the amount is the claim total in the claim currency | - |
| RB-04 | every request, successful or not, is recorded in `reimbursement_attempt` and audited | - |
| RB-05 | a reimbursement is paid by `POST /api/v1/reimbursements/{id}/process`, which sets `PAID`, a processing time and a unique payment reference | `FAILED -> PENDING_PAYMENT` retries are allowed by the lifecycle; `PAID` and `CANCELLED` are terminal |

Duplicate prevention is layered: (1) the claim row is locked so concurrent requests are serialised, (2) an existing reimbursement turns the request into a recorded `DUPLICATE_REJECTED` attempt, (3) a unique constraint on `expense_claim_id`, (4) a database trigger accepting only eligible, fully approved claims. A concurrency test proves that parallel requests for one claim create exactly one reimbursement.

## 6. Reason codes

Reason codes are stable (part of the public contract): they are stored on requests, written to audit events, exported and used by the evaluation module.

| Code | Meaning |
|---|---|
| `INACTIVE_EMPLOYEE` | Employee is not active (inactive or terminated) for the relevant period |
| `INVALID_DATE_RANGE` | Dates are missing, inverted or outside the permitted window |
| `POLICY_LIMIT_EXCEEDED` | A configured policy limit is exceeded |
| `POLICY_NOT_EFFECTIVE` | A policy exists but is not effective on the relevant date |
| `NO_VALID_APPROVER` | No active, authorised approver could be determined for the approval path |
| `ESCALATED_UNAVAILABLE_APPROVER` | Approver unavailable; approval escalated up the reporting line |
| `INSUFFICIENT_BALANCE` | Available leave balance is lower than the requested business days |
| `OVERLAPPING_APPROVED_LEAVE` | Requested dates overlap leave that is already approved |
| `INSUFFICIENT_NOTICE` | Minimum notice period for this leave policy is not satisfied |
| `HOLIDAY_OR_NON_WORKING_DAY_ONLY` | Requested range contains no working day (weekends / public holidays only) |
| `EMPLOYEE_NOT_ELIGIBLE` | Employee has no effective assignment to the requested leave policy |
| `DOCUMENTATION_MISSING` | Supporting documentation is required but was not provided |
| `RECEIPT_MISSING` | A receipt is required for this amount but none was provided |
| `RECEIPT_INVALID` | Receipt is of an unsupported file type or has an invalid status |
| `INVALID_RECEIPT_REFERENCE` | Submitted receipt reference does not exist |
| `DUPLICATE_RECEIPT` | The same receipt (hash) is already attached to another claim line |
| `INSUFFICIENT_BUDGET` | Cost-center budget for the period cannot cover this claim |
| `INVALID_COST_CENTER` | Cost center is missing, unknown or not active |
| `UNAUTHORIZED_COST_CENTER` | Employee is not assigned to / authorised for this cost center |
| `OUTSIDE_EMPLOYMENT_PERIOD` | Transaction date is before joining or after termination |
| `SUSPICIOUS_SPLIT_CLAIM` | Related claims individually stay under an approval threshold but exceed it combined |
| `DUPLICATE_SUBMISSION` | An identical expense line was already submitted |
| `INVALID_CURRENCY` | Currency is unsupported or inconsistent with the claim |
| `INVALID_AMOUNT` | Amount is missing, zero or negative |
| `NO_APPLICABLE_POLICY` | No expense policy exists for this category, country and employee type |
| `UNSUPPORTED_CATEGORY` | Expense category is not supported |
| `NO_LINE_ITEMS` | A claim must contain at least one line item |
| `TOO_MANY_LINE_ITEMS` | A claim may contain at most the configured number of line items |
| `REJECTED_BY_APPROVER` | Rejected by an approver |
| `CANCELLED_BY_REQUESTER` | Cancelled by the requester |
| `RELEASED_BY_ANALYST` | Exception reviewed and released by an operations analyst |
| `REJECTED_BY_ANALYST` | Exception reviewed and rejected by an operations analyst |
| `UNAUTHORIZED_APPROVER` | Actor is not the assigned or authorised approver for this step |
| `SELF_APPROVAL_NOT_ALLOWED` | Requesters cannot approve their own requests |
| `APPROVER_INACTIVE` | Approver is not an active employee |
| `INVALID_STEP_ORDER` | Approval steps must be completed in the configured order |
| `STEP_ALREADY_COMPLETED` | Completed approval steps cannot be overwritten |
| `INVALID_STATE_TRANSITION` | The requested lifecycle transition is not allowed from the current state |
| `DUPLICATE_REIMBURSEMENT` | A reimbursement already exists for this claim |
| `CLAIM_NOT_REIMBURSABLE` | Claim is not eligible and finally approved |
| `EMPLOYEE_NOT_FOUND` | Submitted employee reference does not exist |
| `INVALID_REFERENCE` | A submitted reference could not be resolved |

## 7. Intake: invalid input is preserved

Before any rule runs, `POST /leave-requests` and `POST /expense-claims` pass through intake. Every submission is first stored in `submission_record` exactly as received. Then:

- an unknown **employee** -> `REJECTED_AT_INTAKE` (HTTP 422 with the `submissionId`); there is nobody to attach a canonical record to;
- an unknown **receipt**, unknown **cost center** or inverted **dates** -> the canonical record is created (`ACCEPTED_WITH_ERRORS`) with the canonical foreign key `NULL` and the original value in `submitted_*_ref`, and the evaluation then rejects or holds it with the specific reason;
- an unsupported **category** -> `REJECTED_AT_INTAKE`.

So bad data is never silently discarded, never trusted, and never corrupts a canonical foreign key.

## 8. Audit and explainability

- **`audit_event`**: append-only; every submission, evaluation, status change, approval action, balance movement, reimbursement and every *prevented violation* (self-approval, duplicate reimbursement, invalid transition, ...) is recorded with actor, previous and new state, reason and structured metadata. Categories: `WORKFLOW`, `DECISION`, `VIOLATION`, `BALANCE`, `FINANCIAL`, `SYSTEM`.
- **`decision_trace`**: append-only; per evaluation, the input, every rule outcome (passed / failed / skipped with details), the reason codes, the policy relied on and the approval path.
- **`GET .../explanation`**: assembles both into "why was this decision taken?" (rules evaluated, passed, failed, approval path, event history).

## 9. Rule catalogue: inputs, entity, outcome and test scenario

For every rule: the **input fields** it reads, the **entity** it is evaluated on, the **expected outcome** when it fails (severity / decision and reason code are in sections 2-5) and the **test scenarios** that prove it. *Unit* tests run the pure engine; *integration* tests run the real services against PostgreSQL (Testcontainers); *ground truth* names the designed scenario (`issue_type`) of the generated dataset that is checked by `BaselineGenerationIntegrationTest` / `FullGenerationIntegrationTest` and by the evaluation module.

### Leave

| ID | Input fields | Entity | Outcome on failure | Test scenarios |
|---|---|---|---|---|
| LV-01 | `employee.employment_status`, `termination_date`, request `end_date` | `leave_request` <- `employee` | `REJECTED` | unit `inactiveEmployeeIsRejected`, `anEmployeeTerminatedBeforeTheLeaveEndsIsRejected`; ground truth `INACTIVE_EMPLOYEE` |
| LV-02 | `leave_policy.policy_status`, `effective_from`, `effective_to`; request dates | `leave_request` <- `leave_policy` | `REJECTED` | unit `aPolicyThatExpiresDuringTheRequestIsNotEffective`; ground truth `POLICY_EFFECTIVE_DATE_MISMATCH` |
| LV-03 | `employee.employment_type`, `country`; `leave_policy.eligible_employment_types`, `country`; `employee_policy_assignment` | `leave_request` <- `employee_policy_assignment` | `REJECTED` | unit `contractorsAreNotEligibleForAnnualLeave`, `aCountrySpecificPolicyIsNotAvailableToEmployeesOfAnotherCountry`; integration `ineligibleInsufficientNoticeAndNonWorkingRangesAreRejectedWithExplicitCodes`; ground truth `EMPLOYEE_NOT_ELIGIBLE` |
| LV-04 | `start_date`, `end_date` | `leave_request` | `REJECTED` (request preserved) | unit `invertedAndCrossYearRangesAreInvalidAndSkipDependentRules`; integration `invertedDateRangeIsPreservedAsARejectedRequest`; DB `invertedLeaveRangesAreOnlyAllowedOnRejectedRecords`; ground truth `INVALID_DATE_RANGE` |
| LV-05 | dates, `holiday` of the employee's country, weekend set | `leave_request` <- `holiday_calendar` | `REJECTED` | unit `weekendAndHolidayOnlyRangesAreRejected`, `holidaysReduceTheRequestedDaysAndTheBalanceNeeded`, `BusinessDayCalculatorTest`; integration `holidaysAndWeekendsDoNotCountAsBusinessDays`; ground truth `HOLIDAY_OR_NON_WORKING_DAY_ONLY` |
| LV-06 | `submission_date`, `start_date`, `leave_policy.minimum_notice_days` | `leave_request` | `REJECTED` | unit `insufficientNoticeIsRejected`; ground truth `INSUFFICIENT_NOTICE` |
| LV-07 | business days, `leave_policy.maximum_consecutive_days` | `leave_request` | `REJECTED` | unit `exceedingConsecutiveLimitIsAPolicyLimitViolation`; ground truth `MAX_CONSECUTIVE_DAYS_EXCEEDED` |
| LV-08 | business days, `leave_balance.available_balance` | `leave_request` <- `leave_balance` | `REJECTED`, ledger untouched | unit `insufficientBalanceIsRejected`, `exactlyEnoughBalanceIsAccepted`, `missingBalanceRecordIsInsufficientBalance`; integration `insufficientBalanceIsRejectedWithReasonAndLedgerStaysUntouched`; acceptance `scenario02`; ground truth `NEGATIVE_BALANCE` |
| LV-09 | dates, other `APPROVED` requests of the employee | `leave_request` | `FLAGGED`, both records preserved | unit `overlapWithApprovedLeaveIsFlaggedNotRejected`; integration `overlappingApprovedLeaveIsFlaggedAndBothRecordsArePreserved`; acceptance `scenario03`; ground truth `OVERLAPPING_APPROVED_LEAVE` |
| LV-10 | `leave_policy.requires_documentation`, `documentation_threshold_days`, `supporting_document_ref`, business days | `leave_request` | `NEEDS_REVIEW` | unit `documentationIsRequiredOnlyAboveTheThreshold`; ground truth `DOCUMENTATION_MISSING` |
| LV-11 | planned approval route (rules + reporting line) | `leave_request` <- `approval_rule`, `employee` | `NEEDS_REVIEW` | ground truth `INVALID_APPROVAL_HIERARCHY` (leave variant); the expense variant EX-19 is covered by integration `approverEscalatesWhenTheManagerIsInactiveAndFailsWhenNoApproverExists` |
| LV-LIFECYCLE | requester, status, start date | `leave_request` | invalid transition rejected and audited | unit `leaveRequestsFollowTheDocumentedLifecycle`; integration `validLeaveIsEvaluatedApprovedAndConsumesBalanceExactlyOnce`; ground truth `CANCELLED_LEAVE` |

### Expense

| ID | Input fields | Entity | Outcome on failure | Test scenarios |
|---|---|---|---|---|
| EX-01 | `employee.employment_status` | `expense_claim` <- `employee` | `REJECTED` | unit `inactiveEmployeesCannotClaimButTerminatedEmployeesMayClaimForTheirEmploymentPeriod`; ground truth `INACTIVE_EMPLOYEE` |
| EX-02 | `expense_period_start`, `expense_period_end` | `expense_claim` | `REJECTED` (claim preserved) | unit `invertedPeriodsAndTransactionDatesInTheFutureAreRejected`; integration `invalidCurrencyAmountDateAndPolicyEffectiveDatesAreRejected`; ground truth `INVALID_DATE_RANGE` |
| EX-03 | number of lines, `nexoraflow.expense.max-lines-per-claim` | `expense_claim` | `REJECTED` | unit `aClaimNeedsBetweenOneAndTheConfiguredMaximumNumberOfLines`; API validation (`@NotEmpty`) |
| EX-04 | claim and line `currency`, `country.currency_code`, `currency.supported` | `expense_claim` <- `country`, `currency` | `REJECTED` | unit `amountsMustBePositiveAndTheCurrencyMustBeTheEmployeesCountryCurrency`; integration `invalidCurrencyAmountDateAndPolicyEffectiveDatesAreRejected`; ground truth `INVALID_CURRENCY` |
| EX-05 | line `amount` | `expense_line` | `REJECTED` | same unit and integration tests as EX-04 |
| EX-06 | line `transaction_date`, claim period, `submission_date` | `expense_line` | `REJECTED` | unit `invertedPeriodsAndTransactionDatesInTheFutureAreRejected` |
| EX-07 | line `transaction_date`, `employee.joining_date`, `termination_date` | `expense_line` <- `employee` | `REJECTED` (claim preserved) | unit `transactionsOutsideTheEmploymentPeriodAreRejected`; integration `expensesOutsideTheEmploymentPeriodAreRejectedButPreserved`; ground truth `OUTSIDE_EMPLOYMENT_PERIOD` |
| EX-08 | category, `employee.country`, `employment_type`, transaction date, `expense_policy.effective_from/to` | `expense_line` <- `expense_policy` | `REJECTED` | unit `noApplicablePolicyAndNotYetEffectivePolicyAreDistinguished`, `MiscUnitTests.policiesAreSelectedByEffectiveDateAndEmployeeType`; ground truth `POLICY_EFFECTIVE_DATE_MISMATCH` |
| EX-09 | line `amount`, `expense_policy.maximum_per_transaction` | `expense_line` | `REJECTED` | unit `perTransactionLimitIsEnforcedAtTheBoundary`; acceptance `scenario06`; ground truth `TRANSACTION_LIMIT_EXCEEDED` |
| EX-10 | amounts of the day (this claim + committed claims), `expense_policy.maximum_daily_amount` | `expense_line` | `REJECTED` | unit `dailyAndMonthlyTotalsIncludeWhatIsAlreadyCommitted`; integration `policyLimitsAreEnforcedPerTransactionPerDayAndPerMonth`; ground truth `DAILY_LIMIT_EXCEEDED` |
| EX-11 | amounts of the month, `expense_policy.maximum_monthly_amount` | `expense_line` | `REJECTED` | same unit and integration tests; ground truth `MONTHLY_LIMIT_EXCEEDED` |
| EX-12 | `expense_policy.receipt_required`, `receipt_threshold`; `receipt` type, size, status; `submitted_receipt_ref` | `expense_line` <- `receipt` | `FLAGGED` | unit `receiptsAreRequiredAboveTheThresholdAndValidated`; integration `missingReceiptIsFlagged`; acceptance `scenario05`; ground truth `MISSING_RECEIPT`, `INVALID_RECEIPT_REFERENCE`, `UNSUPPORTED_RECEIPT_TYPE` |
| EX-13 | `receipt.receipt_hash`, other live claims | `expense_line` <- `receipt` | `FLAGGED` | unit `reusedReceiptHashesAreFlagged`; integration `duplicateReceiptAndDuplicateSubmissionAreFlagged`; ground truth `DUPLICATE_RECEIPT` |
| EX-14 | employee, merchant, transaction date, amount | `expense_line` | `FLAGGED` | integration `duplicateReceiptAndDuplicateSubmissionAreFlagged`; ground truth `DUPLICATE_EXPENSE_SUBMISSION` |
| EX-15 | `expense_claim.cost_center_id`, `submitted_cost_center_ref`, `cost_center.status` | `expense_claim` <- `cost_center` | `ON_HOLD`, submitted value preserved | unit `missingOrClosedCostCentersPutTheClaimOnHold`; integration `missingOrInvalidCostCenterIsHeldAndTheOriginalValueIsPreserved`; ground truth `MISSING_COST_CENTER`, `CLOSED_COST_CENTER` |
| EX-16 | `employee_cost_center` effective on the claim date | `expense_claim` <- `employee_cost_center` | `FLAGGED` | unit `usingACostCenterTheEmployeeIsNotAssignedToIsFlagged`; integration `unauthorizedCostCenterUseIsFlagged`; ground truth `UNAUTHORIZED_COST_CENTER_USAGE` |
| EX-17 | `budget` for the claim month, approved spend, pending commitments, claim total | `expense_claim` <- `budget` | `ON_HOLD` | unit `insufficientBudgetPutsTheClaimOnHold`; integration `budgetIsDerivedFromSourceClaimsAndExhaustionPutsTheClaimOnHold`; ground truth `MONTHLY_BUDGET_EXHAUSTED` |
| EX-18 | employee, cost center, category, merchant, dates, amounts in USD, approval boundaries | `expense_claim` / `expense_line` | `FLAGGED`, related claims linked in `detected_anomaly` | `SplitClaimDetectorTest`; unit `aSplitChainIsFollowedEvenWhenItsFirstClaimIsOutsideTheWindowAroundTheNewClaim`, `aClaimThatIsTooFarFromTheChainIsNotLinkedToIt`; integration `suspiciousSplitClaimsAreDetectedAndUnrelatedClaimsAreNot`; acceptance `scenario07`; ground truth `SUSPICIOUS_SPLIT_CLAIM` and the control `SPLIT_CLAIM_CONTROL` |
| EX-19 | planned approval route | `expense_claim` <- `approval_rule`, `employee` | `NEEDS_REVIEW` | integration `approverEscalatesWhenTheManagerIsInactiveAndFailsWhenNoApproverExists`; ground truth `INVALID_APPROVAL_HIERARCHY` |
| EX-TOTAL | line amounts | `expense_claim` | claim total = sum of positive lines | unit `claimTotalIsCalculatedFromLinesNeverSetIndependently`; validation BI-01 |

### Approval and reimbursement

| ID | Input fields | Entity | Outcome on violation | Test scenarios |
|---|---|---|---|---|
| AP-ROUTE | request amount (USD or business days), date, `approval_rule` rows, requester, reporting line | `approval_step` | route of steps per level | `ApprovalRuleResolverTest` (boundaries, ordering, inactive and expired rules, priority, filters); integration `multiLevelApprovalRunsInOrderAndRejectsOutOfOrderActions`, `inactiveApprovalRulesAreIgnoredWhenBuildingTheRoute`; ground truth `MULTI_LEVEL_APPROVAL`, `INACTIVE_APPROVAL_POLICY`; regression `anAmountThatRoundsToTheBoundaryStaysInTheLowerApprovalTierAndPassesRouteValidation` |
| AP-ESCALATION | approver `employment_status`, reporting line | `approval_step` | route escalated; audit `APPROVER_ESCALATED_UNAVAILABLE` | integration `approverEscalatesWhenTheManagerIsInactiveAndFailsWhenNoApproverExists`; ground truth `MANAGER_UNAVAILABLE` |
| AP-01 / AP-SELF | actor, requester, assigned approver | `approval_step` | 403 `SELF_APPROVAL_NOT_ALLOWED`, audited | unit `requesterCannotApproveTheirOwnRequestEvenWhenTheyAreTheAssignedApprover`; integration `requesterCannotApproveOwnClaimAndTheAttemptIsAudited`; DB `theDatabaseRefusesAnApprovalStepAssignedToTheRequester`; acceptance `scenario08`; ground truth `SELF_APPROVAL_ATTEMPT` |
| AP-02 / AP-AUTH | actor, `approver_employee_id`, `approver_role`, job level | `approval_step` | 403 `UNAUTHORIZED_APPROVER` | unit `onlyTheAssignedApproverMayAct`, `approverWithoutTheRequiredJobLevelLacksAuthority`; ground truth `UNAUTHORIZED_APPROVAL_ATTEMPT` |
| AP-03 | actor `employment_status` | `approval_step` | 403 `APPROVER_INACTIVE` | unit `inactiveEmployeesCannotApprove` |
| AP-04 / AP-ORDER | step sequence, status of earlier steps | `approval_step` | 409 `INVALID_STEP_ORDER` | unit `stepsMustRunInOrder`; integration `multiLevelApprovalRunsInOrderAndRejectsOutOfOrderActions`; ground truth `OUT_OF_ORDER_APPROVAL_ATTEMPT` |
| AP-05 / AP-IMMUTABLE | step status | `approval_step` | 409 `STEP_ALREADY_COMPLETED` | unit `completedStepsCannotBeOverwritten`; DB `completedApprovalStepsCannotBeOverwrittenOrDeleted`; concurrency `concurrentApprovalsOfTheSameStepAreSerialisedAndOnlyOneWins`; ground truth `COMPLETED_APPROVAL_OVERWRITE_ATTEMPT` |
| AP-06 | rejection | `approval_step`, request | remaining steps `CANCELLED`, request `REJECTED` | unit `aRejectionRejectsTheRequestAndCancelsTheRemainingSteps`; integration `rejectionStopsTheWorkflowAndNeverCreatesAReimbursement` |
| AP-07 | request status | request | 409 `INVALID_STATE_TRANSITION` | unit `requestsThatAreNotAwaitingApprovalRejectActions` |
| RB-01 / RB-ELIGIBLE | claim status, `eligibility_status`, approval steps | `reimbursement` | `NOT_ELIGIBLE_REJECTED` | unit `claimsThatAreNotApprovedAreNeverReimbursed`, `ineligibleClaimsAndClaimsWithOpenStepsAreRefused`; DB `reimbursementsRequireAnApprovedEligibleClaimAndAreUniquePerClaim`; ground truth `REIMBURSEMENT_BEFORE_APPROVAL` |
| RB-02 / RB-DUP | existing reimbursement of the claim | `reimbursement`, `reimbursement_attempt` | 409 `DUPLICATE_REIMBURSEMENT`, attempt recorded | unit `aSecondRequestIsRejectedRecordedAndCreatesNothing`; concurrency `concurrentReimbursementRequestsCreateExactlyOneReimbursementAndRecordTheRest`; acceptance `scenario09and10`; ground truth `DUPLICATE_REIMBURSEMENT_ATTEMPT` |
| RB-05 | reimbursement status | `reimbursement` | invalid transition rejected | unit `paymentStateTransitionsFollowTheLifecycle` |

### Cross-cutting

| Behaviour | Test scenarios |
|---|---|
| Decision aggregation (`HARD > HOLD > FLAG > REVIEW`, duplicates once, skipped rules ignored) | `LifecycleAndRulesTest.theStrongestFailedRuleDeterminesTheDecision`, `skippedAndPassedRulesNeverAffectTheDecision`, `duplicateReasonCodesAreReportedOnce` |
| Every decision is explained rule by rule | `LeaveEvaluationEngineTest.everyEvaluationExplainsWhichRulesPassedAndFailed` |
| Intake preserves invalid input | integration `unknownEmployeeIsRejectedAtIntakeButPreservedInStaging`, `invalidSubmissionsAreRejectedAtIntakeButStayInStaging`; ground truth `INVALID_EMPLOYEE_REFERENCE`, `UNSUPPORTED_CATEGORY` |
| Transactions roll back completely (including business-ID allocation) | `TransactionAndConcurrencyIntegrationTest.aFailureInsideATransactionRollsBackEverythingIncludingBusinessIdAllocation`, `aFailingFinalApprovalLeavesNoPartialState` |
| Business IDs follow the documented conventions | `MiscUnitTests.businessIdsFollowTheDocumentedConventions` |
| Money: BigDecimal, half-up, fixed documented FX rates | `MiscUnitTests.moneyUsesBigDecimalWithHalfUpRounding`, `currencyConversionUsesTheDocumentedRateAndRoundsToCents` |
| Balances: accrual, pro-rating, reserve / consume / restore | `MiscUnitTests.monthlyAccrualInstalmentsSumToTheAnnualEntitlement`, `newJoinersReceiveAProRatedEntitlement`; integration `validLeaveIsEvaluatedApprovedAndConsumesBalanceExactlyOnce`; DB `balanceLedgerAllowsOnlyOneConsumptionPerRequest` |
| The decision engines never reference the ground truth or the generator | `MiscUnitTests.decisionEnginesNeverReferenceGroundTruthOrTheGenerator` |
| The validator detects corruption and the evaluator detects wrong decisions | `ValidationAndEvaluationNegativeIntegrationTest` |

## 10. Verified against the generated dataset

The 25 validation checks and the independent evaluation against the ground truth run on every generated dataset; the measured results are in the [README](../README.md) and in [validation-report.md](validation-report.md). Running the largest profile found three real defects that the smaller profiles had not exposed; they are described in [design-analysis.md](design-analysis.md) (section "Defects found by the validation and evaluation modules"), together with their fixes and regression tests.
