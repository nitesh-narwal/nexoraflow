# Data dictionary

> Generated from the live PostgreSQL schema (Flyway migrations `V1`..`V6`) - types, nullability, defaults and generated columns come from the database itself. Descriptions are maintained alongside the code. Business-rules version `BR-1.0.0`.

Conventions

- **Business keys are the primary keys** (`EMP-000001`, `LVE-2026-000001`, `EXP-2026-000001`, `EXPL-EXP-2026-000001-01`, `APR-LVE-2026-000001-01`, `RMB-2026-000001`, ...). They are assigned by the application (`IdService`), never by a database sequence, so every API payload, export and audit record uses the same stable, human-readable ID.
- **No wall-clock defaults.** `created_at`, `updated_at`, `occurred_at` ... are supplied by the application clock. During generation this is a simulated clock, which is what makes the dataset reproducible.
- **Money** is `NUMERIC(14,2)` (never floating point); conversion to the reporting currency (USD) uses fixed, documented rates.
- **Dates** (`DATE`) are business dates; timestamps are `TIMESTAMPTZ` in UTC.
- **Soft statuses, no deletes** for business records: requests move through lifecycle states; audit, decision-trace and balance-ledger rows are immutable (triggers).
- **Invalid submissions are preserved.** Invalid references never reach canonical foreign keys; they are kept in `submission_record` and in the `submitted_*_ref` columns.

## Tables

| Table | Group | Purpose |
|---|---|---|
| [`currency`](#currency) | Reference | Supported currencies with a fixed, documented conversion rate to the reporting currency (USD). |
| [`country`](#country) | Reference | Countries in which Nexora operates and the local currency expense claims must use. |
| [`department`](#department) | Master data | Organisational departments. |
| [`employee`](#employee) | Master data | Employees, their reporting line, employment type/status and employment dates. |
| [`cost_center`](#cost-center) | Master data | Cost centers to which expense claims are charged. |
| [`employee_cost_center`](#employee-cost-center) | Master data | Effective-dated assignment of employees to the cost centers they may charge. |
| [`holiday_calendar`](#holiday-calendar) | Reference | One public-holiday calendar per country and year. |
| [`holiday`](#holiday) | Reference | Public holidays; excluded (together with weekends) when business days are counted. |
| [`leave_policy`](#leave-policy) | Policy | Leave policies: entitlement, accrual, carry-forward, notice, consecutive-day limit and documentation rules. |
| [`expense_policy`](#expense-policy) | Policy | Expense policies per category, country and employee type with per-transaction, daily and monthly limits and the receipt rule. |
| [`approval_rule`](#approval-rule) | Policy | Configurable approval routing rules. |
| [`budget`](#budget) | Policy | Monthly budget allocation per cost center. |
| [`employee_policy_assignment`](#employee-policy-assignment) | Policy | Effective-dated assignment of employees to leave policies. |
| [`leave_balance`](#leave-balance) | Leave | Leave balance per employee, policy and calendar year. |
| [`leave_request`](#leave-request) | Leave | Leave requests and their lifecycle. |
| [`leave_balance_transaction`](#leave-balance-transaction) | Leave | Append-only ledger of every balance movement (opening grant, accrual, carry forward, reserve, consume, release, restore). |
| [`receipt`](#receipt) | Expense | Receipt metadata (no binary content is stored). |
| [`expense_claim`](#expense-claim) | Expense | Expense claims (header). |
| [`expense_line`](#expense-line) | Expense | Line items of a claim (1..10 per claim). |
| [`submission_record`](#submission-record) | Intake | Staging area that preserves every submission exactly as received, including submissions with invalid references that could not become canonical records. |
| [`approval_step`](#approval-step) | Approval | One row per approval step of a leave request or expense claim. |
| [`reimbursement`](#reimbursement) | Reimbursement | Reimbursements of approved claims. |
| [`reimbursement_attempt`](#reimbursement-attempt) | Reimbursement | Every reimbursement request including the rejected ones (duplicates, not eligible) so the attempt itself is auditable. |
| [`audit_event`](#audit-event) | Audit | Append-only audit trail of every state change, decision, approval action, balance movement and prevented violation. |
| [`decision_trace`](#decision-trace) | Audit | Append-only, per-evaluation explanation: which rules were evaluated, passed and failed, the reason codes and the approval path. |
| [`detected_anomaly`](#detected-anomaly) | Audit | Anomalies detected by the engines in operation (split claims, duplicate receipts, ...). |
| [`id_counter`](#id-counter) | Reference | Atomic counters behind every business identifier (EMP-000001, LVE-2026-000001, ...). |
| [`generation_run`](#generation-run) | Operations | Best-effort wall-clock log of pipeline runs started through the REST API (command, seed, profile, result summary, status). |
| [`report_snapshot`](#report-snapshot) | Operations | Stored validation and evaluation reports (latest per type is served by the API). |
| [`ground_truth.label`](#ground-truthlabel) | Ground truth | Independent ground truth: the designed expectation for each generated entity. |

## currency

Supported currencies with a fixed, documented conversion rate to the reporting currency (USD). The rates are illustrative assumptions, not market data.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `currency_code` | varchar(3) | no |  | ISO 4217 code (PK). |
| `currency_name` | varchar(60) | no |  | Display name. |
| `rate_to_reporting` | numeric(18,8) | no |  | Fixed rate: 1 unit of this currency = x USD. |
| `rate_effective_from` | date | no |  | Date the rate applies from. |
| `rate_source` | varchar(160) | no |  | Documents that the rate is an illustrative assumption. |
| `supported` | boolean | no | `true` | Whether claims may be made in this currency. |

## country

Countries in which Nexora operates and the local currency expense claims must use.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `country_code` | varchar(2) | no |  | ISO 3166-1 alpha-2 code (PK). |
| `country_name` | varchar(80) | no |  | Country name. |
| `currency_code` | varchar(3) | no |  | Local currency; claims must use it (EX-04). |

## department

Organisational departments.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `department_id` | varchar(40) | no |  | Business key, e.g. `DEP-ENG`. |
| `department_name` | varchar(80) | no |  | Unique department name. |
| `head_employee_id` | varchar(40) | yes |  | Department head (employee business ID). |
| `status` | varchar(12) | no |  | ACTIVE / INACTIVE. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |

## employee

Employees, their reporting line, employment type/status and employment dates. All data is synthetic (fictional `@nexora-technologies.example` e-mail addresses).

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `employee_id` | varchar(40) | no |  | Business key `EMP-000001` (PK). |
| `first_name` | varchar(80) | no |  | Synthetic first name (Datafaker). |
| `last_name` | varchar(80) | no |  | Synthetic last name (Datafaker). |
| `email` | varchar(160) | no |  | Unique (case-insensitive) fictional address `@nexora-technologies.example`. |
| `phone` | varchar(40) | yes |  | Synthetic phone number. |
| `department_id` | varchar(40) | no |  | Department. |
| `designation` | varchar(80) | no |  | Job title. |
| `job_level` | integer | no |  | 1 (CEO) .. 5 (junior). Used to resolve MANAGER (<= 3) and DEPARTMENT_HEAD (<= 2) approvers. |
| `manager_id` | varchar(40) | yes |  | Direct manager; NULL only for the CEO. Self-reference and cycles are rejected by trigger. |
| `country` | varchar(2) | no |  | Country of employment (drives leave policy, holidays, currency). |
| `location` | varchar(80) | yes |  | City. |
| `employment_type` | varchar(20) | no |  | FULL_TIME / PART_TIME / CONTRACTOR / INTERN. |
| `employment_status` | varchar(20) | no |  | ACTIVE / INACTIVE / TERMINATED. |
| `joining_date` | date | no |  | First day of employment. |
| `termination_date` | date | yes |  | Last day of employment; set if and only if status = TERMINATED. |
| `cost_center_id` | varchar(40) | yes |  | Primary cost center (denormalised from `employee_cost_center`). |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |
| `version` | bigint | no | `0` | Optimistic-locking version; a concurrent update of the same row fails instead of silently overwriting. |

## cost_center

Cost centers to which expense claims are charged.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `cost_center_id` | varchar(40) | no |  | Business key, e.g. `CC-0001` (PK). |
| `cost_center_code` | varchar(40) | no |  | Human-readable code such as `ENG-IN-01`. |
| `cost_center_name` | varchar(120) | no |  | Display name. |
| `department_id` | varchar(40) | no |  | Owning department. |
| `budget_owner_id` | varchar(40) | yes |  | Employee responsible for the budget. |
| `country` | varchar(2) | no |  | Country the cost center belongs to. |
| `status` | varchar(12) | no |  | ACTIVE / CLOSED / SUSPENDED. Only ACTIVE cost centers accept new claims (EX-15). |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |

## employee_cost_center

Effective-dated assignment of employees to the cost centers they may charge. A database exclusion constraint forbids overlapping PRIMARY assignments.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `assignment_id` | varchar(40) | no |  | Business key `ECC-000001`. |
| `employee_id` | varchar(40) | no |  | Employee. |
| `cost_center_id` | varchar(40) | no |  | Cost center the employee may charge. |
| `assignment_type` | varchar(12) | no |  | PRIMARY or SECONDARY. |
| `effective_from` | date | no |  | Assignment start. |
| `effective_to` | date | yes |  | Assignment end (NULL = open). |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |

## holiday_calendar

One public-holiday calendar per country and year.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `calendar_id` | varchar(40) | no |  | Business key `HC-DE-2026`. |
| `country` | varchar(2) | no |  | Country. |
| `calendar_year` | integer | no |  | Calendar year. |
| `calendar_name` | varchar(120) | no |  | Display name. |

## holiday

Public holidays; excluded (together with weekends) when business days are counted.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `holiday_id` | varchar(40) | no |  | Business key `HOL-DE-2026-01`. |
| `calendar_id` | varchar(40) | no |  | Parent calendar. |
| `holiday_date` | date | no |  | Date of the holiday. |
| `holiday_name` | varchar(120) | no |  | Name of the holiday. |
| `holiday_type` | varchar(20) | no |  | PUBLIC / REGIONAL / COMPANY. |

## leave_policy

Leave policies: entitlement, accrual, carry-forward, notice, consecutive-day limit and documentation rules. Policies are effective-dated and versioned by row.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `leave_policy_id` | varchar(40) | no |  | Business key, e.g. `LPOL-ANNUAL-IN`. |
| `policy_name` | varchar(120) | no |  | Display name. |
| `leave_type` | varchar(20) | no |  | ANNUAL / SICK / UNPAID / PARENTAL. |
| `country` | varchar(2) | yes |  | Country the policy applies to; NULL = global. |
| `annual_entitlement` | numeric(6,2) | no |  | Days granted per year. |
| `accrual_frequency` | varchar(20) | no |  | ANNUAL_UPFRONT (full grant on 1 January) or MONTHLY (twelve cumulative-rounded instalments). |
| `carry_forward_limit` | numeric(6,2) | no | `0` | Maximum days carried into the next year. |
| `maximum_consecutive_days` | integer | no |  | Longest request in business days (LV-07). |
| `minimum_notice_days` | integer | no | `0` | Calendar days between submission and start (LV-06). |
| `requires_documentation` | boolean | no | `false` | Whether long requests need a supporting document (LV-10). |
| `documentation_threshold_days` | integer | no | `0` | Documentation is needed above this number of business days. |
| `eligible_employment_types` | varchar(100) | no |  | Comma-separated employment types that may use the policy. |
| `effective_from` | date | no |  | First day on which the row is effective (inclusive). |
| `effective_to` | date | yes |  | Last day on which the row is effective (inclusive); NULL = open-ended. |
| `policy_status` | varchar(12) | no |  | ACTIVE / INACTIVE / DRAFT. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |

## expense_policy

Expense policies per category, country and employee type with per-transaction, daily and monthly limits and the receipt rule. Several effective-dated versions of one policy may exist.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `expense_policy_id` | varchar(40) | no |  | Business key, e.g. `XPOL-MEALS-DE`. |
| `policy_name` | varchar(120) | no |  | Display name. |
| `expense_category` | varchar(30) | no |  | TRAVEL / MEALS / LODGING / OFFICE_SUPPLIES / TRAINING / CLIENT_ENTERTAINMENT. |
| `country` | varchar(2) | no |  | Country the policy applies to. |
| `employee_type` | varchar(20) | no |  | Employment type the policy applies to, or ALL. |
| `currency` | varchar(3) | no |  | Currency in which the limits are expressed (the country's currency). |
| `maximum_per_transaction` | numeric(18,2) | no |  | Limit per line (EX-09). |
| `maximum_daily_amount` | numeric(18,2) | no |  | Limit per employee, category and day (EX-10). |
| `maximum_monthly_amount` | numeric(18,2) | no |  | Limit per employee, category and month (EX-11). |
| `receipt_required` | boolean | no |  | Whether a receipt is required above the threshold (EX-12). |
| `receipt_threshold` | numeric(18,2) | no | `0` | Receipt needed when the line amount is above this value. |
| `requires_manager_approval` | boolean | no | `true` | Informational flag; approval routing is driven by `approval_rule`. |
| `effective_from` | date | no |  | First day on which the row is effective (inclusive). |
| `effective_to` | date | yes |  | Last day on which the row is effective (inclusive); NULL = open-ended. |
| `policy_status` | varchar(12) | no |  | ACTIVE / INACTIVE / DRAFT. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |

## approval_rule

Configurable approval routing rules. Thresholds live in rows, never in code. A rule applies when it is ACTIVE, effective on the date, its scope filters match and minimum_amount < amount <= maximum_amount.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `approval_rule_id` | varchar(40) | no |  | Business key, e.g. `AR-EX-002`. |
| `workflow_type` | varchar(10) | no |  | LEAVE or EXPENSE. |
| `country` | varchar(2) | yes |  | Optional scope filter (NULL = any). |
| `department_id` | varchar(40) | yes |  | Optional scope filter (NULL = any). |
| `expense_category` | varchar(30) | yes |  | Optional scope filter (NULL = any). |
| `minimum_amount` | numeric(18,2) | no | `0` | Exclusive lower bound. |
| `maximum_amount` | numeric(18,2) | yes |  | Inclusive upper bound; NULL = unbounded. |
| `amount_unit` | varchar(10) | no |  | DAYS (business days) for leave, USD (reporting-currency equivalent) for expenses. |
| `approval_level` | integer | no |  | Level (1..5) the rule contributes a step to; levels run in ascending order. |
| `approver_role` | varchar(30) | no |  | DIRECT_MANAGER / MANAGER / DEPARTMENT_HEAD / FINANCE_REVIEWER / FINANCE_HEAD. |
| `approver_designation` | varchar(80) | yes |  | Designation required for FINANCE_* roles (Finance Manager / VP Finance). |
| `effective_from` | date | no |  | First day on which the row is effective (inclusive). |
| `effective_to` | date | yes |  | Last day on which the row is effective (inclusive); NULL = open-ended. |
| `priority` | integer | no | `100` | Lower number wins when several rules of one level apply. |
| `rule_status` | varchar(12) | no |  | ACTIVE / INACTIVE. INACTIVE and expired rules are never applied. |
| `description` | varchar(300) | yes |  | Human-readable description. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |

## budget

Monthly budget allocation per cost center. Spend is NOT stored here: the budget position is always derived from the source claims.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `budget_id` | varchar(60) | no |  | Business key `BUD-CC-0001-2026-01`. |
| `cost_center_id` | varchar(40) | no |  | Cost center. |
| `fiscal_year` | integer | no |  | Fiscal year. |
| `period_start` | date | no |  | First day of the budget period. |
| `period_end` | date | no |  | Last day of the budget period. |
| `allocated_amount` | numeric(18,2) | no |  | Amount allocated for the period. |
| `currency` | varchar(3) | no |  | Budget currency. |
| `commit_pending` | boolean | no | `true` | When true, claims awaiting approval count against the budget. |
| `status` | varchar(12) | no |  | ACTIVE / CLOSED. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |

## employee_policy_assignment

Effective-dated assignment of employees to leave policies.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `assignment_id` | varchar(40) | no |  | Business key `EPA-000001`. |
| `employee_id` | varchar(40) | no |  | Employee business ID (EMP-000001). |
| `leave_policy_id` | varchar(40) | no |  | Assigned leave policy. |
| `effective_from` | date | no |  | Assignment start. |
| `effective_to` | date | yes |  | Assignment end (NULL = open). |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |

## leave_balance

Leave balance per employee, policy and calendar year. `available_balance` is a database generated column: opening + accrued + carried forward - used - pending.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `leave_balance_id` | varchar(40) | no |  | Business key `LBAL-000001`. |
| `employee_id` | varchar(40) | no |  | Employee business ID (EMP-000001). |
| `leave_policy_id` | varchar(40) | no |  | Policy the balance belongs to. |
| `balance_year` | integer | no |  | Calendar year. |
| `opening_balance` | numeric(7,2) | no | `0` | Granted at the start of the year (ANNUAL_UPFRONT). |
| `accrued_balance` | numeric(7,2) | no | `0` | Accrued so far (MONTHLY policies). |
| `carried_forward_balance` | numeric(7,2) | no | `0` | Carried in from the previous year. |
| `used_balance` | numeric(7,2) | no | `0` | Days consumed by finally approved leave. |
| `pending_balance` | numeric(7,2) | no | `0` | Days reserved for requests currently in approval. |
| `available_balance` | numeric(7,2) | yes | GENERATED: `((opening_balance + accrued_balance) + carried_forward_balance) - used_balance) - pending_balance)` | GENERATED: opening + accrued + carried forward - used - pending. |
| `last_updated_at` | timestamptz | no |  | Timestamp of the last ledger posting. |
| `version` | bigint | no | `0` | Optimistic-locking version; a concurrent update of the same row fails instead of silently overwriting. |

## leave_request

Leave requests and their lifecycle. Invalid employee/policy references are never stored here; they are preserved in `submission_record`.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `leave_request_id` | varchar(40) | no |  | Business key `LVE-2026-000001`. |
| `employee_id` | varchar(40) | no |  | Employee business ID (EMP-000001). |
| `leave_policy_id` | varchar(40) | no |  | Requested policy. |
| `start_date` | date | no |  | First day of leave. |
| `end_date` | date | no |  | Last day of leave (same calendar year as start). |
| `requested_days` | numeric(6,2) | no |  | Inclusive calendar days as submitted. |
| `reason` | varchar(500) | yes |  | Free-text reason. |
| `submission_date` | date | no |  | Business date of submission (drives the notice rule). |
| `submitted_at` | timestamptz | no |  | Submission timestamp. |
| `request_status` | varchar(20) | no |  | SUBMITTED / PENDING_APPROVAL / NEEDS_REVIEW / FLAGGED / APPROVED / REJECTED / CANCELLED. |
| `calculated_business_days` | numeric(6,2) | yes |  | Business days computed by the engine (weekends and holidays excluded). |
| `decision` | varchar(20) | yes |  | Decision of the last evaluation (or reviewer). |
| `decision_reason` | varchar(500) | yes |  | Comma-separated reason codes. |
| `supporting_document_ref` | varchar(200) | yes |  | Reference to the supporting document, if any. |
| `cancelled_at` | timestamptz | yes |  | Cancellation timestamp. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |
| `version` | bigint | no | `0` | Optimistic-locking version; a concurrent update of the same row fails instead of silently overwriting. |

## leave_balance_transaction

Append-only ledger of every balance movement (opening grant, accrual, carry forward, reserve, consume, release, restore). Balances reconcile with this ledger (check BI-02).

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `txn_id` | varchar(40) | no |  | Business key `LBT-0000001`. |
| `leave_balance_id` | varchar(40) | no |  | Balance the movement belongs to. |
| `leave_request_id` | varchar(40) | yes |  | Request that caused the movement (NULL for grants / accruals). |
| `txn_type` | varchar(20) | no |  | OPENING_GRANT / ACCRUAL / CARRY_FORWARD / PENDING_RESERVE / PENDING_RELEASE / CONSUME / RESTORE / MANUAL_ADJUSTMENT. |
| `period_key` | varchar(20) | yes |  | Accrual period (e.g. `2026-03`); unique per balance for accruals, which makes accrual idempotent. |
| `opening_delta` | numeric(7,2) | no | `0` | Change of `opening_balance`. |
| `accrued_delta` | numeric(7,2) | no | `0` | Change of `accrued_balance`. |
| `carried_forward_delta` | numeric(7,2) | no | `0` | Change of `carried_forward_balance`. |
| `used_delta` | numeric(7,2) | no | `0` | Change of `used_balance`. |
| `pending_delta` | numeric(7,2) | no | `0` | Change of `pending_balance`. |
| `txn_date` | date | no |  | Business date of the movement. |
| `occurred_at` | timestamptz | no |  | Timestamp of the movement. |
| `actor` | varchar(40) | no |  | Employee ID or SYSTEM. |
| `reason` | varchar(300) | no |  | Explanation. |

## receipt

Receipt metadata (no binary content is stored). The SHA-256 hash is used to detect the same receipt being reused.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `receipt_id` | varchar(40) | no |  | Business key `RCP-000001`. |
| `employee_id` | varchar(40) | no |  | Employee business ID (EMP-000001). |
| `file_name` | varchar(200) | no |  | Synthetic file name. |
| `file_type` | varchar(20) | no |  | PDF / JPG / JPEG / PNG / HEIC are supported; others are flagged (RECEIPT_INVALID). |
| `file_size_bytes` | bigint | no |  | Size in bytes (limit configurable, default 10 MiB). |
| `uploaded_at` | timestamptz | no |  | Upload timestamp. |
| `receipt_hash` | varchar(64) | no |  | SHA-256 of the (synthetic) content; identical hashes on different lines mean the receipt was reused (EX-13). |
| `receipt_status` | varchar(20) | no |  | VALID / INVALID / QUARANTINED. Anything but VALID fails EX-12. |
| `source_reference` | varchar(120) | yes |  | Upload channel. |

## expense_claim

Expense claims (header). The claim is the unit of approval; its total is the sum of its positive line amounts.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `expense_claim_id` | varchar(40) | no |  | Business key `EXP-2026-000001`. |
| `employee_id` | varchar(40) | no |  | Employee business ID (EMP-000001). |
| `claim_date` | date | no |  | Business (booking) date; selects the budget period. |
| `expense_period_start` | date | no |  | First day covered by the claim. |
| `expense_period_end` | date | no |  | Last day covered by the claim. |
| `submission_date` | date | no |  | Business date of submission. |
| `submitted_at` | timestamptz | no |  | Submission timestamp. |
| `cost_center_id` | varchar(40) | yes |  | Canonical cost center; NULL when none was supplied or the supplied one is unknown. |
| `submitted_cost_center_ref` | varchar(80) | yes |  | Cost center exactly as submitted (preserved even when invalid). |
| `currency` | varchar(3) | no |  | Claim currency (must equal the employee's country currency). |
| `total_amount` | numeric(18,2) | no |  | Sum of the positive line amounts. |
| `claim_status` | varchar(20) | no |  | SUBMITTED / PENDING_APPROVAL / NEEDS_REVIEW / FLAGGED / ON_HOLD / APPROVED / REJECTED / CANCELLED. |
| `eligibility_status` | varchar(20) | no |  | NOT_EVALUATED / ELIGIBLE / INELIGIBLE / REVIEW_REQUIRED. Reimbursement requires ELIGIBLE. |
| `decision` | varchar(20) | yes |  | Decision of the last evaluation (or reviewer). |
| `decision_reason` | varchar(500) | yes |  | Comma-separated reason codes. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |
| `version` | bigint | no | `0` | Optimistic-locking version; a concurrent update of the same row fails instead of silently overwriting. |

## expense_line

Line items of a claim (1..10 per claim).

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `expense_line_id` | varchar(40) | no |  | Business key `EXPL-EXP-2026-000001-01`. |
| `expense_claim_id` | varchar(40) | no |  | Parent claim. |
| `line_number` | integer | no |  | 1..10. |
| `expense_policy_id` | varchar(40) | yes |  | Policy resolved for the line (NULL until evaluated or when none applies). |
| `expense_category` | varchar(30) | no |  | TRAVEL / MEALS / LODGING / OFFICE_SUPPLIES / TRAINING / CLIENT_ENTERTAINMENT. |
| `merchant_name` | varchar(160) | no |  | Merchant. |
| `transaction_date` | date | no |  | Date of the transaction. |
| `description` | varchar(300) | yes |  | Description. |
| `amount` | numeric(18,2) | no |  | Amount in the line currency (may be invalid on rejected submissions; see `line_status`). |
| `currency` | varchar(3) | no |  | Line currency. |
| `amount_reporting` | numeric(18,2) | yes |  | USD equivalent at the fixed documented rate. |
| `receipt_id` | varchar(40) | yes |  | Canonical receipt; NULL when absent or unknown. |
| `submitted_receipt_ref` | varchar(80) | yes |  | Receipt reference exactly as submitted. |
| `cost_center_id` | varchar(40) | yes |  | Line-level cost center. |
| `line_status` | varchar(20) | no |  | SUBMITTED / VALID / FLAGGED / INVALID. |
| `validation_reason` | varchar(300) | yes |  | Comma-separated reason codes explaining the status. |

## submission_record

Staging area that preserves every submission exactly as received, including submissions with invalid references that could not become canonical records.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `submission_id` | varchar(40) | no |  | Business key `SUB-000001`. |
| `request_type` | varchar(10) | no |  | LEAVE or EXPENSE. |
| `received_at` | timestamptz | no |  | Receipt timestamp. |
| `submitted_by_ref` | varchar(80) | yes |  | Submitting employee exactly as supplied (may not exist). |
| `raw_payload` | jsonb | no |  | JSON of the submission as received. |
| `intake_status` | varchar(30) | no |  | ACCEPTED / ACCEPTED_WITH_ERRORS / REJECTED_AT_INTAKE. |
| `validation_errors` | jsonb | yes |  | JSON list of intake errors (e.g. EMPLOYEE_NOT_FOUND). |
| `leave_request_id` | varchar(40) | yes |  | Canonical leave request created from it, if any. |
| `expense_claim_id` | varchar(40) | yes |  | Canonical claim created from it, if any. |

## approval_step

One row per approval step of a leave request or expense claim. A trigger forbids self-approval, overwriting completed steps and deleting steps.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `approval_step_id` | varchar(40) | no |  | Business key `APR-EXP-2026-000001-01`. |
| `request_type` | varchar(10) | no |  | LEAVE or EXPENSE. |
| `leave_request_id` | varchar(40) | yes |  | Target leave request (exactly one of leave/expense). |
| `expense_claim_id` | varchar(40) | yes |  | Target claim (exactly one of leave/expense). |
| `request_id` | varchar(40) | yes | GENERATED: `COALESCE(leave_request_id, expense_claim_id)` | GENERATED: whichever of the two targets is set. |
| `step_sequence` | integer | no |  | Position in the route (1..n). |
| `approval_level` | integer | no |  | Approval level of the rule that produced the step. |
| `approver_role` | varchar(30) | no |  | Role of the approver. |
| `approver_employee_id` | varchar(40) | no |  | Resolved approver. |
| `approval_status` | varchar(12) | no |  | WAITING / PENDING / APPROVED / REJECTED / CANCELLED / SKIPPED / ESCALATED. |
| `approval_rule_id` | varchar(40) | yes |  | Rule that produced the step. |
| `escalated_from_step_id` | varchar(40) | yes |  | Step this one replaced when the approver was unavailable or after an explicit escalation. |
| `assigned_at` | timestamptz | yes |  | When the step became actionable. |
| `action_at` | timestamptz | yes |  | When the approver acted. |
| `comments` | varchar(500) | yes |  | Approver comment. |
| `decision_reason` | varchar(300) | yes |  | Reason / outcome text. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |
| `version` | bigint | no | `0` | Optimistic-locking version; a concurrent update of the same row fails instead of silently overwriting. |

## reimbursement

Reimbursements of approved claims. At most one per claim (unique constraint); a trigger only accepts ELIGIBLE, APPROVED claims whose approval steps are all completed.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `reimbursement_id` | varchar(40) | no |  | Business key `RMB-2026-000001`. |
| `expense_claim_id` | varchar(40) | no |  | Reimbursed claim (unique: one reimbursement per claim). |
| `employee_id` | varchar(40) | no |  | Employee business ID (EMP-000001). |
| `approved_amount` | numeric(18,2) | no |  | Amount paid out (the claim total). |
| `currency` | varchar(3) | no |  | Currency of the payment. |
| `reimbursement_status` | varchar(20) | no |  | PENDING_PAYMENT / PAID / FAILED / CANCELLED. |
| `approved_at` | timestamptz | no |  | When the reimbursement was created (after final approval). |
| `processed_at` | timestamptz | yes |  | When it was paid. |
| `payment_reference` | varchar(60) | yes |  | Unique payment reference `PAY-2026-000001`. |
| `created_at` | timestamptz | no |  | Creation timestamp (simulated clock during generation, so it is deterministic). |
| `updated_at` | timestamptz | no |  | Last-modification timestamp (simulated clock during generation). |
| `version` | bigint | no | `0` | Optimistic-locking version; a concurrent update of the same row fails instead of silently overwriting. |

## reimbursement_attempt

Every reimbursement request including the rejected ones (duplicates, not eligible) so the attempt itself is auditable.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `attempt_id` | varchar(40) | no |  | Business key `RMA-000001`. |
| `expense_claim_id` | varchar(40) | no |  | Claim the request was for. |
| `attempted_at` | timestamptz | no |  | Timestamp. |
| `outcome` | varchar(24) | no |  | CREATED / DUPLICATE_REJECTED / NOT_ELIGIBLE_REJECTED. |
| `reason_code` | varchar(40) | yes |  | DUPLICATE_REIMBURSEMENT / CLAIM_NOT_REIMBURSABLE for rejected attempts. |
| `reimbursement_id` | varchar(40) | yes |  | Reimbursement created or already existing. |
| `requested_by` | varchar(40) | no |  | Requester. |
| `details` | varchar(300) | yes |  | Explanation. |

## audit_event

Append-only audit trail of every state change, decision, approval action, balance movement and prevented violation.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `event_id` | varchar(40) | no |  | Business key `EVT-00000001`. |
| `event_category` | varchar(12) | no |  | WORKFLOW / DECISION / VIOLATION (prevented rule violations) / BALANCE / FINANCIAL / SYSTEM. |
| `entity_type` | varchar(30) | no |  | LEAVE_REQUEST / EXPENSE_CLAIM / APPROVAL_STEP / REIMBURSEMENT / ... |
| `entity_id` | varchar(60) | no |  | Entity the event is about. |
| `event_type` | varchar(60) | no |  | See the event vocabulary in `AuditEventType`. |
| `previous_state` | varchar(30) | yes |  | State before the event. |
| `new_state` | varchar(30) | yes |  | State after the event. |
| `actor` | varchar(40) | no |  | Employee ID or SYSTEM. |
| `occurred_at` | timestamptz | no |  | Timestamp (simulated clock during generation). |
| `reason` | varchar(500) | yes |  | Explanation / reason code. |
| `metadata` | jsonb | yes |  | JSON with event-specific details. |

## decision_trace

Append-only, per-evaluation explanation: which rules were evaluated, passed and failed, the reason codes and the approval path.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `trace_id` | varchar(40) | no |  | Business key `DEC-0000001`. |
| `request_type` | varchar(10) | no |  | LEAVE or EXPENSE. |
| `request_id` | varchar(40) | no |  | Evaluated request. |
| `evaluation_number` | integer | no |  | 1 for the first evaluation, 2 after a re-evaluation (e.g. after a cost-center correction). |
| `evaluated_at` | timestamptz | no |  | Timestamp. |
| `decision` | varchar(20) | no |  | Decision of the evaluation. |
| `reason_codes` | varchar(500) | yes |  | Comma-separated reason codes. |
| `policy_ref` | varchar(120) | yes |  | Policy the decision relied on. |
| `rule_version` | varchar(20) | no |  | Business-rules version (BR-1.0.0). |
| `trace` | jsonb | no |  | JSON: input, rules passed/failed (with details), decision, reason codes, approval path. |

## detected_anomaly

Anomalies detected by the engines in operation (split claims, duplicate receipts, ...). Separate from the ground truth, which the engines never see.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `anomaly_id` | varchar(40) | no |  | Business key `ANM-000001`. |
| `anomaly_domain` | varchar(20) | no |  | LEAVE / EXPENSE / APPROVAL / REIMBURSEMENT. |
| `anomaly_type` | varchar(40) | no |  | e.g. SUSPICIOUS_SPLIT_CLAIM, DUPLICATE_RECEIPT. |
| `entity_type` | varchar(30) | no |  | LEAVE_REQUEST / EXPENSE_CLAIM / ... |
| `entity_id` | varchar(40) | no |  | Affected entity. |
| `related_entity_ids` | varchar(500) | yes |  | Comma-separated related entities. |
| `severity` | varchar(10) | no |  | LOW / MEDIUM / HIGH / CRITICAL. |
| `reason_code` | varchar(40) | no |  | Reason code. |
| `detected_at` | timestamptz | no |  | Detection timestamp. |
| `details` | jsonb | yes |  | JSON evidence. |
| `status` | varchar(12) | no |  | OPEN / CONFIRMED / DISMISSED. |

## id_counter

Atomic counters behind every business identifier (EMP-000001, LVE-2026-000001, ...). One row per counter key; advanced with a single INSERT .. ON CONFLICT DO UPDATE .. RETURNING statement so concurrent callers never receive the same number.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `counter_key` | varchar(60) | no |  | Counter name, e.g. `EMP`, `LVE-2026`, `EXP-2026`, `EVT`. |
| `next_value` | bigint | no |  | Next number to hand out (starts at 1). |

## generation_run

Best-effort wall-clock log of pipeline runs started through the REST API (command, seed, profile, result summary, status). CLI runs write the same information to `data/manifests/generation_manifest.json`, which also holds the logical checksums. Excluded from every checksum.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `run_id` | varchar(40) | no |  | Run identifier. |
| `command` | varchar(40) | no |  | Pipeline command. |
| `seed` | bigint | no |  | Seed used. |
| `size_profile` | varchar(10) | no |  | SMALL / MEDIUM / LARGE. |
| `config` | jsonb | no |  | JSON with the run configuration (reference date). |
| `application_version` | varchar(40) | no |  | Application version. |
| `rules_version` | varchar(20) | no |  | Business-rules version. |
| `started_at` | timestamptz | no |  | Wall-clock start (operational metadata only). |
| `finished_at` | timestamptz | yes |  | Wall-clock end (operational metadata only). |
| `status` | varchar(12) | no |  | RUNNING / COMPLETED / FAILED. |
| `current_stage` | varchar(60) | yes |  | Stage in progress. |
| `row_counts` | jsonb | yes |  | JSON result summary of the run (stages and row counts). |
| `logical_checksums` | jsonb | yes |  | Reserved; not populated - logical checksums are in the manifest and the reproducibility report. |
| `error_message` | varchar(1000) | yes |  | Failure message. |

## report_snapshot

Stored validation and evaluation reports (latest per type is served by the API).

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `report_id` | bigint | no |  | Snapshot identifier. |
| `report_type` | varchar(30) | no |  | VALIDATION / EVALUATION / OPERATIONS. |
| `generated_at` | timestamptz | no |  | Wall-clock time of the report. |
| `content` | jsonb | no |  | The report as JSON. |

## ground_truth.label

Independent ground truth: the designed expectation for each generated entity. Lives in a separate `ground_truth` schema, is written by the generator and read only by the evaluation module; the business engines never read it.

| Column | Type | Null | Default / generated | Description |
|---|---|:-:|---|---|
| `scenario_id` | varchar(60) | no |  | Scenario / planned-entity identifier. |
| `entity_type` | varchar(30) | no |  | LEAVE_REQUEST / EXPENSE_CLAIM / SUBMISSION. |
| `entity_id` | varchar(60) | no |  | Entity the label is about. |
| `issue_type` | varchar(50) | no |  | Designed scenario (e.g. NEGATIVE_BALANCE) or NONE for a valid record. |
| `is_anomaly` | boolean | no |  | True for deliberately defective scenarios. |
| `expected_outcome` | varchar(30) | no |  | Expected final decision / status. |
| `expected_reason_code` | varchar(50) | yes |  | Expected reason code(s). |
| `expected_approval_path` | varchar(500) | yes |  | Expected approver chain computed by the independent oracle. |
| `expected_reimbursement_status` | varchar(20) | no |  | Expected reimbursement result (e.g. NONE, ONE, DUPLICATE_PREVENTED). |
| `severity` | varchar(10) | no |  | Designed severity: NONE / LOW / MEDIUM / HIGH / CRITICAL. |
| `explanation` | varchar(1000) | no |  | Why the entity is labelled this way. |
| `evidence_type` | varchar(30) | no |  | Where the evaluation must find evidence (DECISION_REASON, AUDIT_EVENT, ANOMALY_RECORD, APPROVAL_PATH, REIMBURSEMENT_ATTEMPT, BALANCE_LEDGER, INTAKE, NONE). |
| `related_entity_ids` | varchar(500) | yes |  | Related entities. |
| `business_rule_ids` | varchar(200) | yes |  | Rules the scenario exercises (LV-.., EX-.., ...). |

