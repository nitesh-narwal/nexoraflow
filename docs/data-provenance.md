# Data provenance

Where every record in the NexoraFlow dataset comes from, how it was produced, how it can be reproduced, and what it can and cannot be used for. The figures on this page are **computed from the exported dataset** (`data/`), not typed by hand.

## 1. Identity

| | |
|---|---|
| Dataset | NexoraFlow synthetic dataset |
| Dataset version | `1.0.0+seed20261022.medium` |
| Company | Nexora Technologies - **fictional** |
| Contains real personal data | **false** |
| Seed / profile / reference date | `20261022` / `MEDIUM` / `2026-10-22` |
| Application / business-rules version | `1.0.0` / `BR-1.0.0` |
| Faker library | Datafaker 2.5.4 (`net.datafaker:datafaker`, pinned in `pom.xml`; a different version may produce different names) |
| Overall logical checksum (SHA-256) | `d88f798469546a74306ed0d04ea3d3d71a9290b037318ab8de3e46e23e8991b5` |
| Generation timestamp (wall clock) | `2026-11-12T20:09:20.584606620Z` - operational metadata only, excluded from every checksum; all timestamps *inside* the dataset come from the seeded simulation clock |
| External sources | none: no public dataset, API or file is used; everything is produced by the generator from the seed |
| Data transformations | none beyond serialisation of the canonical tables: CSV and JSON carry the same values (ISO-8601 UTC timestamps, `YYYY-MM-DD` dates, dot decimals, UTF-8); a SQL `NULL` is an empty field in CSV and an omitted key in JSON; relationships are the business IDs |

## 2. Provenance summary

| Data | Source | Real? |
|---|---|---|
| Names, phone numbers, cities | Datafaker with one locale per country (DE, GB, IN, SG, US), driven by a seeded `java.util.Random` | no - synthetic |
| E-mail addresses | derived from the synthetic name (`first.last`, with a numeric suffix where needed to keep it unique) on the reserved, non-routable domain `nexora-technologies.example` | no - synthetic, cannot be delivered to anyone |
| Organisation (departments, hierarchy, job levels, cost centers) | `OrgChartBuilder` / `MasterDataGenerator`: one CEO, one head per department, managers, individual contributors; cost centers per department and country | no - designed |
| Leave and expense policies, approval rules, budgets | `PolicyCatalog` / `ExpenseCatalog` (fixed, documented values; see [business-rules.md](business-rules.md)) | no - designed, illustrative |
| Public holidays | `HolidayCatalog`: illustrative calendars for 2025-2027 per country | partly real dates, **not** an official calendar |
| Currency rates | fixed illustrative rates (EUR 1.09, GBP 1.27, INR 0.012, SGD 0.74, USD 1.00 to USD) | no - not market data |
| Merchants, descriptions, amounts | `ExpenseCatalog` / `ClaimFactory`: per-category merchant and description lists, amounts drawn inside the policy limits | no - synthetic |
| Receipts | metadata only; the hash is the SHA-256 of a unique synthetic token (not of any real file); no files exist | no |
| Workflow history (statuses, approvals, balances, audit trail, reimbursements) | **produced by the real services** during a simulated-time run; nothing is inserted directly | no - consequences of the business rules |
| Ground truth | planners with their own restatement of the policy (`BusinessPolicyBaseline`, `ApprovalOracle`) and shadow ledgers | no - designed answer key |

No real person, company, account or receipt is represented. If a generated name happens to equal a real person's name, that is coincidence.

## 3. How the data is generated

```
seed -> per-stage Random (SHA-256(seed:stage)) -> master -> leave -> expense -> anomaly scenarios
          each stage: plan (+ independent ground truth) -> simulate against the real services in simulated time
```

1. **Master data** - departments, an organisation chart (1 CEO, department heads, managers, individual contributors), employees (type, status, joining dates), cost centers and assignments, policies, approval rules, holiday calendars, monthly budgets, leave balances (opening grant, carry forward, accrual).
2. **Baseline leave** - 800 planned requests, each valid by construction (shadow balance ledger, notice, consecutive-day limit, no overlap, no weekends / holidays) with a scripted approver behaviour (approve, reject, leave pending, cancel). Each employee's requests are planned by walking a cursor through the year with a gap of 4-41 days, and the pool is trimmed to exactly 800, so volume is **front-loaded** (February-March peak). That shape is an artefact of the method, not designed seasonality.
3. **Baseline expenses** - 1,000 planned claims with 1-10 lines, valid against policy, with receipts, a valid cost center, a budget that can absorb them and an approval route that matches the amount.
4. **Anomaly scenarios** - the designed defective cases of section 5, added on top of the baseline.
5. **Simulation** - `SimulationEngine` replays all plans as a discrete-event simulation: submissions, evaluations, approver decisions, cancellations, payroll runs and monthly accruals happen in strict chronological order against the *real* workflow services while the application clock is pinned to each event's time. Events after the cutoff (2026-10-22T18:00Z) never execute, so some requests remain pending.
6. **Ground truth** - each planner writes the expected outcome, reason code, approval path and reimbursement result of every entity into `ground_truth.label` using only its own logic and the organisation chart. The business engines never read it.

**Why this design:** if the dataset were produced by the engines and then judged by the engines, any engine bug would be baked into both the data and the answer key. Here the expectation is derived independently and the data is produced by the real services, so a disagreement is a real finding.

### Reservation of scenario subjects

Employees used by anomaly scenarios (every 4th eligible individual contributor, the direct reports of deliberately unavailable managers, and the CEO for the hierarchy scenario) are **excluded from the baseline workload**. Without this, a baseline claim could accidentally duplicate, overlap or split with a scenario record and make the ground truth ambiguous. Within the scenarios the same care applies: an employee whose leave balance a scenario deliberately exhausted is not used by a later scenario, and an employee carries at most one big-ticket travel / training scenario per calendar month (monthly limits are finite). Both rules were added after the LARGE profile showed what happens without them (see [design-analysis.md](design-analysis.md), section 7).

## 4. What the dataset contains (default profile, measured)

| Entity | Rows | Distribution |
|---|---:|---|
| Employees | 400 | countries: IN 154, US 85, GB 67, DE 50, SG 44; departments: DEP-ENG 136, DEP-SAL 71, DEP-CS 44, DEP-OPS 40, DEP-PRD 32, DEP-FIN 28, DEP-MKT 28, DEP-HR 21 |
| | | employment type: FULL_TIME 357, CONTRACTOR 21, INTERN 12, PART_TIME 10; status: ACTIVE 379, INACTIVE 17, TERMINATED 4; job level: 1 1, 2 8, 3 30, 4 46, 5 315 |
| Leave requests | 892 | status: APPROVED 630, REJECTED 108, PENDING_APPROVAL 93, CANCELLED 48, FLAGGED 7, NEEDS_REVIEW 6; policy: LPOL-ANNUAL-IN 193, LPOL-SICK 186, LPOL-ANNUAL-US 169, LPOL-ANNUAL-GB 138, LPOL-ANNUAL-DE 115, LPOL-ANNUAL-SG 91 |
| Expense claims | 1217 | status: APPROVED 877, PENDING_APPROVAL 134, REJECTED 124, FLAGGED 65, ON_HOLD 16, NEEDS_REVIEW 1; currency: INR 441, USD 285, GBP 179, EUR 171, SGD 138, ZZZ 3 |
| Expense lines | 2599 | category: MEALS 1182, TRAVEL 455, OFFICE_SUPPLIES 375, LODGING 319, TRAINING 142, CLIENT_ENTERTAINMENT 126; lines per claim: 1 640, 2 270, 3 120, 4 42, 5 44, 6 53, 7 31, 8 17 |
| Approval steps | 2169 | status: APPROVED 1738, PENDING 227, REJECTED 138, CANCELLED 52, WAITING 11, SKIPPED 3; role: DIRECT_MANAGER 1639, MANAGER 293, FINANCE_REVIEWER 202, DEPARTMENT_HEAD 32, FINANCE_HEAD 3 |
| Reimbursements | 877 | status: PAID 585, PENDING_PAYMENT 292; attempts: CREATED 877, DUPLICATE_REJECTED 12, NOT_ELIGIBLE_REJECTED 4 |
| Submission records | 2119 | intake: ACCEPTED 2085, ACCEPTED_WITH_ERRORS 24, REJECTED_AT_INTAKE 10 |

Row counts of every table are in `data/manifests/generation_manifest.json`; the leave policies, expense policies and approval rules are in `data/generated/`.

Observations that matter when using the data:

- Currency `ZZZ` (3 claims) and receipt type `EXE` are deliberate (scenarios `INVALID_CURRENCY`, `UNSUPPORTED_RECEIPT_TYPE`).
- Claims dates run from 2026-01-12 to 2026-10-20; leave submissions from 2026-01-09 to 2026-10-20; some leave starts later in 2026 and in January 2027.
- Claims in the default dataset have between 1 and 8 lines; the engine and the API accept up to 10 (the maximum is configurable).
- There are more reimbursements than a typical company because every approved claim is reimbursed; 'PENDING_PAYMENT' reimbursements are simply not yet paid by the simulated payroll run.

## 5. Ground truth and anomaly scenarios

`ground_truth.label` holds **2119** labelled entities: **247** belong to a designed anomaly scenario, the others are valid records or deliberate negative / positive controls. Every label states the expected outcome, reason code, approval path, reimbursement status, the rules it exercises and **where the evidence must be found** (`evidence_type`: decision reason, audit event, anomaly record, approval path, reimbursement attempt, balance ledger or intake).

| Scenario (`issue_type`) | Anomaly | Entities | Expected outcome | Expected reason code | Evidence | Rules |
|---|:-:|---:|---|---|---|---|
| `CANCELLED_LEAVE` | no | 6 | CANCELLED | CANCELLED_BY_REQUESTER | BALANCE_LEDGER | LV-LIFECYCLE |
| `CLOSED_COST_CENTER` | yes | 2 | ON_HOLD | INVALID_COST_CENTER | DECISION_REASON | EX-15 |
| `COMPLETED_APPROVAL_OVERWRITE_ATTEMPT` | yes | 4 | APPROVED | STEP_ALREADY_COMPLETED | AUDIT_EVENT | AP-IMMUTABLE |
| `DAILY_LIMIT_EXCEEDED` | yes | 4 | REJECTED | POLICY_LIMIT_EXCEEDED | DECISION_REASON | EX-10 |
| `DOCUMENTATION_MISSING` | yes | 5 | NEEDS_REVIEW | DOCUMENTATION_MISSING | DECISION_REASON | LV-10 |
| `DUPLICATE_EXPENSE_SUBMISSION` | yes | 8 | FLAGGED | DUPLICATE_SUBMISSION | DECISION_REASON | EX-14 |
| `DUPLICATE_RECEIPT` | yes | 8 | FLAGGED | DUPLICATE_RECEIPT | DECISION_REASON | EX-13 |
| `DUPLICATE_REIMBURSEMENT_ATTEMPT` | yes | 8 | APPROVED | DUPLICATE_REIMBURSEMENT | REIMBURSEMENT_ATTEMPT | RB-DUP |
| `EMPLOYEE_NOT_ELIGIBLE` | yes | 4 | REJECTED | EMPLOYEE_NOT_ELIGIBLE | DECISION_REASON | LV-03 |
| `HOLIDAY_OR_NON_WORKING_DAY_ONLY` | yes | 5 | REJECTED | HOLIDAY_OR_NON_WORKING_DAY_ONLY | DECISION_REASON | LV-05 |
| `INACTIVE_APPROVAL_POLICY` | yes | 6 | APPROVED | - | APPROVAL_PATH | AR-EX-008,AR-EX-009 |
| `INACTIVE_EMPLOYEE` | yes | 10 | REJECTED | INACTIVE_EMPLOYEE | DECISION_REASON | EX-01 |
| `INSUFFICIENT_NOTICE` | yes | 4 | REJECTED | INSUFFICIENT_NOTICE | DECISION_REASON | LV-06 |
| `INVALID_APPROVAL_HIERARCHY` | yes | 2 | NEEDS_REVIEW | NO_VALID_APPROVER | DECISION_REASON | EX-19 |
| `INVALID_CURRENCY` | yes | 6 | REJECTED | INVALID_CURRENCY | DECISION_REASON | EX-04 |
| `INVALID_DATE_RANGE` | yes | 12 | REJECTED | INVALID_DATE_RANGE | DECISION_REASON | EX-02 |
| `INVALID_EMPLOYEE_REFERENCE` | yes | 8 | REJECTED_AT_INTAKE | EMPLOYEE_NOT_FOUND | INTAKE | INTAKE |
| `INVALID_RECEIPT_REFERENCE` | yes | 4 | FLAGGED | INVALID_RECEIPT_REFERENCE | DECISION_REASON | EX-12 |
| `MANAGER_UNAVAILABLE` | yes | 8 | APPROVED | ESCALATED_UNAVAILABLE_APPROVER | AUDIT_EVENT | AP-ESCALATION |
| `MAX_CONSECUTIVE_DAYS_EXCEEDED` | yes | 5 | REJECTED | POLICY_LIMIT_EXCEEDED | DECISION_REASON | LV-07 |
| `MISSING_COST_CENTER` | yes | 8 | ON_HOLD | INVALID_COST_CENTER | DECISION_REASON | EX-15 |
| `MISSING_RECEIPT` | yes | 15 | FLAGGED | RECEIPT_MISSING | DECISION_REASON | EX-12 |
| `MONTHLY_BUDGET_EXHAUSTED` | yes | 6 | ON_HOLD | INSUFFICIENT_BUDGET | DECISION_REASON | EX-17 |
| `MONTHLY_LIMIT_EXCEEDED` | yes | 3 | REJECTED | POLICY_LIMIT_EXCEEDED | DECISION_REASON | EX-11 |
| `MULTI_LEVEL_APPROVAL` | no | 6 | APPROVED | - | APPROVAL_PATH | AR-EX-003..AR-EX-006 |
| `NEGATIVE_BALANCE` | yes | 12 | REJECTED | INSUFFICIENT_BALANCE | DECISION_REASON | LV-08 |
| `NONE` | no | 1844 | APPROVED | - | NONE | EX-01..EX-19 |
| `OUTSIDE_EMPLOYMENT_PERIOD` | yes | 12 | REJECTED | OUTSIDE_EMPLOYMENT_PERIOD | DECISION_REASON | EX-07 |
| `OUT_OF_ORDER_APPROVAL_ATTEMPT` | yes | 4 | APPROVED | INVALID_STEP_ORDER | AUDIT_EVENT | AP-ORDER |
| `OVERLAPPING_APPROVED_LEAVE` | yes | 7 | FLAGGED | OVERLAPPING_APPROVED_LEAVE | DECISION_REASON | LV-09 |
| `POLICY_EFFECTIVE_DATE_MISMATCH` | yes | 9 | REJECTED | POLICY_NOT_EFFECTIVE | DECISION_REASON | EX-08 |
| `REIMBURSEMENT_BEFORE_APPROVAL` | yes | 4 | PENDING_APPROVAL | CLAIM_NOT_REIMBURSABLE | REIMBURSEMENT_ATTEMPT | RB-ELIGIBLE |
| `SELF_APPROVAL_ATTEMPT` | yes | 10 | APPROVED | SELF_APPROVAL_NOT_ALLOWED | AUDIT_EVENT | AP-SELF |
| `SPLIT_CLAIM_CONTROL` | no | 16 | APPROVED | - | NONE | EX-18 |
| `SUSPICIOUS_SPLIT_CLAIM` | yes | 18 | FLAGGED | SUSPICIOUS_SPLIT_CLAIM | ANOMALY_RECORD | EX-18 |
| `TRANSACTION_LIMIT_EXCEEDED` | yes | 8 | REJECTED | POLICY_LIMIT_EXCEEDED | DECISION_REASON | EX-09 |
| `UNAUTHORIZED_APPROVAL_ATTEMPT` | yes | 4 | APPROVED | UNAUTHORIZED_APPROVER | AUDIT_EVENT | AP-AUTH |
| `UNAUTHORIZED_COST_CENTER_USAGE` | yes | 8 | FLAGGED | UNAUTHORIZED_COST_CENTER | DECISION_REASON | EX-16 |
| `UNSUPPORTED_CATEGORY` | yes | 2 | REJECTED_AT_INTAKE | UNSUPPORTED_CATEGORY | INTAKE | INTAKE |
| `UNSUPPORTED_RECEIPT_TYPE` | yes | 4 | FLAGGED | RECEIPT_INVALID | DECISION_REASON | EX-12 |

The seven mandatory scenarios of the brief map to: `NEGATIVE_BALANCE` (negative leave balance), `OVERLAPPING_APPROVED_LEAVE`, `OUTSIDE_EMPLOYMENT_PERIOD` (expense outside employment dates), `SUSPICIOUS_SPLIT_CLAIM` (with `SPLIT_CLAIM_CONTROL` as the negative control), `MISSING_COST_CENTER`, `SELF_APPROVAL_ATTEMPT` and `DUPLICATE_REIMBURSEMENT_ATTEMPT`. The others are the additional scenarios the brief allows.

`NONE` rows are valid ordinary records: the expectation is that the system raises **no** exception for them (a violation there would be a false positive).

## 6. Reproducibility

**Contract:** the same seed + configuration + application version + business-rules version + Datafaker version produce the same *logical* dataset - the same rows with the same values, whatever the machine, time zone or wall-clock time.

How it is achieved:

- each stage derives its own `java.util.Random` from `SHA-256(seed:stage)`; the algorithm of `java.util.Random` is fixed by the JDK specification, so sequences are identical across JVMs and platforms;
- the simulation pins the application clock to each event's simulated time, and no column has a wall-clock default;
- every query that feeds a planner is ordered (sorted by ID), the simulation is single-threaded and ties are broken by creation order;
- per-table **logical checksums** (SHA-256 over the canonical content, excluding wall-clock metadata) are written to `data/manifests/` and compared between runs.

What was verified (executed, not assumed):

| Check | Result |
|---|---|
| Two generations in one JVM (integration test) | identical logical checksums |
| Staged commands (`generate-master`, `-leave`, `-expense`, `-anomalies`) vs. `generate-all` (integration test) | identical |
| Load of the exported SQL seed into an empty migrated database (integration test) | all table checksums reproduced |
| JVM in `Asia/Kolkata` vs. JVM in `UTC` (MEDIUM) | identical |
| Host run vs. Docker run (MEDIUM) | identical per-table checksums |
| `reproducibility-check` in Docker (MEDIUM, 2 runs) | `identical: true`, no differences, overall checksum above |

Re-create it: `scripts/full-pipeline.sh` (or `docker compose --profile tools run --rm pipeline`) and compare `overallLogicalChecksum` in `data/manifests/generation_manifest.json` with the value in section 1.

## 7. Files

| Path | Content |
|---|---|
| `data/generated/*.csv`, `*.json` | one file pair per table (canonical data; ISO-8601 timestamps, dot decimals, UTF-8) |
| `data/generated/sql/nexoraflow_seed.sql` | all data as SQL `INSERT`s, loadable into a database migrated by Flyway |
| `data/ground_truth/ground_truth.csv`, `.json` | the independent answer key |
| `data/manifests/generation_manifest.json` | seed, profile, versions, row counts, per-table logical checksums |
| `data/manifests/checksums.json` | SHA-256 of every exported file + overall logical checksum |
| `data/manifests/validation_summary.json` | validation result of this dataset |
| `data/reports/` | `validation_report.json`, `evaluation_report.json`, `evaluation_summary.md`, `decision_summary.md`, `operational_metrics.json` |
| `docs/validation-report.md` | human-readable validation + evaluation report (generated) |

## 8. Limits of this data

- It is synthetic: distributions are designed, not observed. Seasonality, approval behaviour, amounts and the anomaly rate (about 11.7 % of labelled entities) are choices, chosen to exercise every rule, not to resemble a real company's statistics.
- Because the answer key and the scenarios have one author, agreement between engines and ground truth shows consistency with the *documented* rules, not correctness against reality (see [assumptions.md](assumptions.md), D-4).
- Receipts have no content, FX rates are fixed, calendars are illustrative.
- It must not be used to train or benchmark a fraud model: the anomalies are rule-detectable by construction.
