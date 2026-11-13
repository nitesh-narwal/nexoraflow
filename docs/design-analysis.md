# Design analysis, requirements traceability and FDE perspective

This is the analysis the brief asks for before implementation, kept current with what was actually built. It explains how the brief was read, how every requirement is met and verified, which risks were identified, which defects the project's own validation found, and what the system means for the people who would run it.

## 1. The customer problem

Nexora Technologies (fictional) runs leave and expense approvals through e-mail threads and spreadsheets. HR checks leave balances by hand, Finance checks expense policy by eye, managers approve in whatever order they see the message, and nobody can later answer *why* something was approved. The brief asks for a system that automates the workflows **and** for a believable, reproducible dataset on which the automation can be proven correct.

| Problem today | What goes wrong | How NexoraFlow addresses it | Evidence |
|---|---|---|---|
| Manual leave verification | holidays, weekends, overlaps and notice rules are checked inconsistently | one deterministic engine (LV-01 .. LV-11) applied to every request; business days from real holiday calendars | `LeaveEvaluationEngineTest`, `BusinessDayCalculatorTest`, acceptance scenarios 01-03 |
| Incorrect balance calculations | days deducted twice, balances that cannot be reconciled | reserve at evaluation, consume once at final approval, immutable ledger, generated `available_balance` | `BI-02`, `BI-03`, `BI-04` validation checks; `balanceLedgerAllowsOnlyOneConsumptionPerRequest` |
| Inconsistent expense policies | the same expense accepted for one employee and refused for another | effective-dated policies per category, country and employee type, applied by one engine (EX-01 .. EX-19) | `ExpenseEvaluationEngineTest`, acceptance scenarios 04-07 |
| Approval delays | requests stall when a manager is away; nobody knows who must act | configurable routing, escalation up the reporting line, pending-approval queue per approver | `approverEscalatesWhenTheManagerIsInactive...`, `GET /approvals/pending`, report *approvals* |
| Missing documentation | claims paid without receipts; long sick leave without certificate | receipt and documentation rules turn into `FLAGGED` / `NEEDS_REVIEW`, never into a silent approval | EX-12, LV-10, ground-truth scenarios `MISSING_RECEIPT`, `DOCUMENTATION_MISSING` |
| Unauthorized approvals | people approve their own requests or act out of order | self-approval, authority, order and immutability enforced in the service **and** in the database | `ApprovalServiceTest`, `theDatabaseRefusesAnApprovalStepAssignedToTheRequester`, acceptance scenario 08 |
| Duplicate reimbursements | the same claim paid twice | one reimbursement per claim by lock + unique constraint + trigger; every attempt recorded | `concurrentReimbursementRequestsCreateExactlyOne...`, acceptance scenarios 09-10 |
| Lack of auditability | "who approved this and on what basis?" cannot be answered | append-only audit trail, per-evaluation decision traces, `/explanation` endpoints | `audit_event`, `decision_trace`, validation of lifecycle replay (BI-10) |

## 2. Reading of the brief

- **Two products in one.** (1) A workflow system (leave, expense, approval, reimbursement) and (2) a data-engineering pipeline that produces a deterministic, explainable, independently verified synthetic dataset. The second is not a seed script: it drives the first, and an independent answer key proves the first.
- **Deterministic, explainable and testable** is the guiding constraint. No business decision depends on randomness or on machine learning.
- **Independence of the answer key.** The most important engineering requirement is that the ground truth is not produced by the engine it judges (brief section 14, rule 9). Everything in the generator is organised around that.
- **Synthetic does not mean random.** Statuses, balances, approvals and reimbursements in the dataset are *consequences* of running the real services on planned, rule-valid inputs (rules 1, 2 and 14).

## 3. Requirements traceability

| Brief | Requirement | Implemented in | Verified by |
|---|---|---|---|
| 2, 3 | Java 21, Spring Boot, JPA, PostgreSQL, Flyway, Maven, Swagger, Docker, Datafaker, JUnit 5, Mockito, Testcontainers | `pom.xml`, `Dockerfile`, `docker-compose.yml` | the build; `docker compose` run in the verification (README "Results") |
| 4 | Five business actors | data model (`employee`, approval roles, analyst review, system / payroll actor) | acceptance scenarios; `POST .../review`, `POST /reimbursements/{id}/process` |
| 5 A | Master data | `employee`, `department`, `costcenter`, `policy`, `budget` packages; employee, cost-center and policy endpoints | `MasterDataGeneratorIntegrationTest`, `ApiAcceptanceTest` |
| 5 B | Synthetic generator | `generator.*` | `BaselineGenerationIntegrationTest`, `FullGenerationIntegrationTest`, reproducibility tests |
| 5 C | Leave engine | `leave.*` | section 3 of [business-rules.md](business-rules.md) |
| 5 D | Expense engine | `expense.*`, `budget.*` | section 3 of [business-rules.md](business-rules.md) |
| 5 E | Approval engine | `approval.*` | `ApprovalRuleResolverTest`, `ApprovalServiceTest` |
| 5 F | Reimbursement | `reimbursement.*` | `ReimbursementServiceTest`, concurrency test |
| 5 G | Evaluation and validation | `evaluation.*`, `validation.*` | `ValidationAndEvaluationNegativeIntegrationTest` |
| 5 H | Reporting and export | `reporting.*`, `export.*` | `operationalReportsAreCalculatedFromSourceRecords`, `ExportAndReproducibilityIntegrationTest` |
| 6 | Normalised schema, required entities, constraints, indexes | `V1..V6` migrations | `DatabaseConstraintsIntegrationTest`; [database-schema.md](database-schema.md) |
| 7 | Business ID conventions | `IdService` | `MiscUnitTests.businessIdsFollowTheDocumentedConventions`; validation DI-03 |
| 8 | Fictional company, volumes, sequence, reproducibility | generator, `SizeProfile`, `GeneratorSeeds` | `BaselineGenerationIntegrationTest` (exact 400 / 800 / 1,000); [data-provenance.md](data-provenance.md) |
| 9 | Leave logic (business days, eligibility, balance, outcomes) | `leave.*` | LV rules, ledger checks |
| 10 | Expense logic (validation, amounts, receipts, budget, outcomes) | `expense.*`, `budget.*` | EX rules |
| 11 | Configurable approval routing, multi-level, restrictions, auditability | `approval_rule` table, `ApprovalService` | AP rules |
| 12 | Reimbursement only after final approval, never twice | `ReimbursementService`, trigger, unique constraint | RB rules |
| 13 | The seven mandatory anomaly scenarios + others | `generator.anomaly.*` | ground-truth catalogue in [data-provenance.md](data-provenance.md) (the 7 mandatory + 32 further scenario types) |
| 14 | Independent ground truth, evaluation metrics | schema `ground_truth`, `evaluation.*` | `groundTruthLivesInAnIsolatedSchemaAndCoversEveryMandatoryScenario`; `decisionEnginesNeverReferenceGroundTruthOrTheGenerator` |
| 15 | Validation of integrity, business rules and data quality | `validation.*` (25 checks) | `theGeneratedDatasetPassesEveryCheck...`; every deliberate corruption is reported |
| 16 | REST API | controllers listed in [api-documentation.md](api-documentation.md) | `ApiAcceptanceTest` |
| 17 | Metrics calculated from source records | `ReportingService` | `operationalReportsAreCalculatedFromSourceRecords` and the regression test for unsupported currencies |
| 18 | Audit and traceability | `audit.*` | audit tests, BI-10 |
| 19 | Project structure | the feature packages of the brief (`common`, `employee`, `department`, `costcenter`, `policy`, `leave`, `expense`, `approval`, `reimbursement`, `budget`, `audit`, `reporting`, `generator`, `evaluation`, `configuration`) under `me.niteshh.nexoraflow` (the scaffold's group; the brief's example says `com.nexora.nexoraflow`), plus `validation`, `export` and `intake`; `docs/`, `data/`, `scripts/` as specified; DTOs instead of entities in the API, thin controllers | [architecture.md](architecture.md), `controllersStayThinAndDoNotExposeEntities` (unit test) |
| 20 | Unit, integration, acceptance tests | `src/test/java/...` | the test run reported in the README |
| 21 | Documentation | `README.md`, `docs/*` | this folder |
| 22 | CSV, JSON, SQL export in the prescribed layout | `export.*` | `exportProducesCsvJsonSqlGroundTruthAndManifests...` |
| 23 | Optional AI | not built (deliberate, see [assumptions.md](assumptions.md) I-8) | - |
| 24 | FDE perspective | section 7 of this document | - |
| 26 | The 20 engineering rules | section 8 of this document | - |
| 27 | Definition of Done | section 9 of this document | - |

## 4. Core design decisions (summary)

The full rationale is in [architecture.md](architecture.md); the choices that matter most:

1. **Rules are objects with an ID, a severity and a reason code.** A decision is the strongest failed rule; every failed rule is reported; skipped rules are shown. That single shape gives explainability, testability and a stable contract for the evaluation module.
2. **Business violations are results, not exceptions.** The audit event of a prevented self-approval must commit; an exception would roll it back.
3. **The database is a second implementation of the invariants** (constraints, exclusion constraints, generated columns, guard triggers). The negative tests prove it catches what the application would miss.
4. **Nothing derived is stored as truth** (available balance, budget position, claim total).
5. **A simulated clock and per-stage random streams** make the dataset a pure function of seed, configuration and versions.
6. **Invalid input is preserved** in `submission_record` and `submitted_*_ref` columns; canonical foreign keys stay valid.

## 5. Strategies

### Generation
Master data -> baseline leave -> baseline expenses -> anomaly scenarios. Each stage derives its own random stream; each planner produces valid-by-construction inputs plus the expected outcome from its own restatement of the policy; a single-threaded discrete-event simulation replays everything against the real services in simulated time. Details: [data-provenance.md](data-provenance.md).

### Anomalies
39 designed scenario types (36 anomalous, 3 controls or positive scenarios; the seven mandatory ones and further ones: duplicate receipt and submission, policy and cost-center edge cases, approver unavailability, out-of-order and overwrite attempts, unsupported categories, ...). Where a false alarm is plausible a *negative control* exists (e.g. `SPLIT_CLAIM_CONTROL`: same merchant on adjacent days, combined amount below the threshold). Scenario subjects are drawn from a reserved pool so that scenarios cannot collide with the ordinary workload or with each other (see section 7 for the defect that taught this).

### Testing
- **Unit** - pure engines and calculators; no database.
- **Integration** - real services on PostgreSQL (Testcontainers): lifecycles, constraints, transactions and concurrency, generator, validation / evaluation (including deliberate corruption), export and reproducibility.
- **Acceptance** - the ten scenarios named by the brief through the REST API, plus the error contract and the operational reports.
- **Property of the whole** - a generated dataset must pass 25 validation checks *and* equal the independent ground truth.

## 6. Risks identified and how they were handled

| Risk | Handling |
|---|---|
| The engine is graded by itself | ground truth in a separate schema, produced by independent logic; a unit test fails if an engine class references it |
| Non-determinism (hash iteration order, unordered queries, time zones, thread timing) | sorted inputs, single-threaded simulation, UTC business dates, pinned clock; verified across JVM time zones and between host and Docker |
| Boundary amounts (exactly 500.00 USD) | one documented rule (lower bound exclusive, upper inclusive, amounts in cents); regression test (section 7) |
| Race conditions (double approval, double reimbursement, concurrent overlap) | row locks, unique constraints, re-check at final approval; concurrency tests |
| Bad input silently lost or trusted | staging table, canonical FK stay valid, tests |
| Scope creep ("unnecessary infrastructure", rule 15) | no message broker, cache or auth server; Docker Compose has only the database, the app and a one-shot job |
| Over-claiming | numbers in the docs are generated from the dataset or taken from an executed run; limitations are listed in [assumptions.md](assumptions.md) |

## 7. Defects found by the validation and evaluation modules

The independent validation and evaluation are the project's own safety net. They passed with 100 % on the SMALL and MEDIUM profiles, so the LARGE profile (2,000 employees, 4,000 leave requests, 5,000 claims, five times the anomaly volume) was generated as well. The first LARGE run was **not** clean: validation reported one failed check (3 findings) and the evaluation reported 4 mismatches (4 of 1,171 anomalies not detected: anomaly recall 99.66 %, decision accuracy 99.96 %). Tracing every finding back to its cause produced four defects, in four different places - which is the point of having an independent check:

| # | Symptom | Where | Root cause | Fix | Regression test |
|---|---|---|---|---|---|
| 1 | `BI-05` failed for 3 claims: the route had `DIRECT_MANAGER` where the validator expected `MANAGER` (the same at 2,000 USD) | **validator** | it compared the *unrounded* USD amount (500.00004) with the 500 boundary, while the engine and the documentation use the claim total in cents (500.00). It also used `double` for money, which the brief forbids. | cent-rounded amount, exact `BigDecimal` comparison | `anAmountThatRoundsToTheBoundaryStaysInTheLowerApprovalTierAndPassesRouteValidation` |
| 2 | three `CLIENT_ENTERTAINMENT` claims (200, 180 and 160 USD at one merchant on days 0, 1 and 4) were not flagged although together 540 USD > 500 | **engine** | the candidate search looked only within the window around the *new* claim, so a chain whose first claim lay more than 3 days from the last was never completed. The documented rule says the dates are *chained*, and `SplitClaimDetector` implements that. | the search range is widened until no further claim falls inside it (bounded) | `aSplitChainIsFollowedEvenWhenItsFirstClaimIsOutsideTheWindowAroundTheNewClaim`, `aClaimThatIsTooFarFromTheChainIsNotLinkedToIt` |
| 3 | a leave request of the `SELF_APPROVAL_ATTEMPT` scenario was rejected for `INSUFFICIENT_BALANCE`, and later four approved-by-design travel claims were rejected for `POLICY_LIMIT_EXCEEDED` (monthly limit, EX-11) | **generator** | scenario subjects are drawn round-robin from a reserved pool. At MEDIUM the pool never wrapped onto an employee that an earlier scenario had drained; at LARGE it did: (a) the `NEGATIVE_BALANCE` scenario deliberately uses up a balance, (b) several big-ticket claims landed on one employee in one month and together exceeded the monthly travel limit. | scenarios record subjects whose balance they exhausted; an employee carries at most one big-ticket scenario per category and month (a first version keyed by month only was too coarse: small control scenarios exhausted the pool, so the registry is per category) | verified by the opt-in `ScaleProfileIntegrationTest` and the LARGE run |
| 4 | the *operational report* counted 1,214 claims although 1,217 exist | **reporting** | the SQL joined the currency table with an inner join, so claims in a currency the system does not support (rejected as `INVALID_CURRENCY`) vanished from the counts | left join; such claims are counted and add nothing to USD amounts (they cannot be converted) | `claimsInAnUnsupportedCurrencyAreStillCountedByTheReports` |

Defect 3 took three iterations, and the last one is instructive: the first version of the subject rule passed LARGE but **broke the SMALL profile** (a 50-employee company has a tiny reserved pool; excluding every employee whose balance was drained left too few candidates, a multi-level claim landed on an employee with other limits and was rejected). The integration tests caught it immediately. The final rule *prefers* employees that are free and falls back to the plain round robin when the pool is too small. Lesson: a generator change must be verified at **every** profile - the full test suite covers SMALL, the opt-in test and the documented runs cover MEDIUM and LARGE.

Defect 4 was not found by LARGE but while checking the report numbers against the dataset for this documentation.

**A last audit of the finished project against the brief** (section by section) found three smaller gaps, all closed: (a) the evaluation report lacked two of the metrics listed in the brief, *foreign-key validation failures* and *duplicate business identifiers* (now `controls.foreignKeyValidationFailures` and `controls.duplicateBusinessIdentifiers`, taken from the validation checks DI-02 and DI-01, with a test that corrupts data and requires them to move); (b) the acceptance tests asserted API responses but did not all assert the resulting database state and audit events, which the brief requires for every scenario (they do now); (c) the documentation described the reimbursement as a manual step although the code creates it at final approval, and one API example showed an empty audit query (both corrected). Two brief items are deliberately not met and documented instead: the cost center is not a routing criterion, and employee-specific working calendars are not supported ([assumptions.md](assumptions.md)).

**Result after the fixes:** the LARGE profile was generated, validated and evaluated again with the corrected code - see the README, section "Results". All 25 validation checks pass (one informational), no mismatch against the ground truth.

What this shows: (a) the independent checks find real problems - in the engine, in the validator and in the generator; (b) a perfect score on one profile proves the absence of *those* defects at *that* scale, not of defects in general; (c) the evaluation must be re-run whenever the dataset parameters change. The fixes were made where the defect was (validator, engine, generator, report) - never by loosening the ground truth to match. The opt-in test (`./mvnw test -Dtest=ScaleProfileIntegrationTest -Dnexora.scale=LARGE`) keeps the check repeatable.

## 8. The 20 engineering rules

| # | Rule | Status |
|---|---|---|
| 1 | no business decisions by random assignment | decisions come from rules; randomness only picks *inputs* inside valid ranges |
| 2 | no independently randomised calculated values | balances, totals, amounts in USD, routes are computed (`available_balance` generated, claim total derived, route from rules) |
| 3 | no child with a non-existent parent | foreign keys; invalid references preserved in staging instead (check DI-02, DQ-07) |
| 4 | no silent discarding of invalid submissions | `submission_record`, `ACCEPTED_WITH_ERRORS` / `REJECTED_AT_INTAKE` |
| 5 | no overwriting of approval history | trigger + service check; tests |
| 6 | no self-approval | service **and** trigger |
| 7 | no reimbursement before final approval | service **and** trigger |
| 8 | no duplicate reimbursement | lock, unique constraint, trigger, recorded attempts |
| 9 | ground truth never exposed to the engine | separate schema; no repository maps it; an architecture unit test |
| 10 | no floating point for financial calculations | `BigDecimal` / `NUMERIC(14,2)` everywhere money is stored or compared (the validator was corrected, section 7); `double` remains only for ratios, text similarity, random weights and generator planning inputs that are converted to `BigDecimal` with explicit rounding |
| 11 | thresholds not hard-coded in services | `approval_rule` rows; split detection reads them; a separate restatement lives only in the *ground-truth* oracle |
| 12 | effective dates respected | policies, rules, assignments, budgets are effective-dated; EX-08, LV-02 |
| 13 | weekends and holidays are not ordinary days | `BusinessDayCalculator` with per-country holidays |
| 14 | nothing approved "because it was generated as approved" | the simulation executes the real approval service |
| 15 | no unnecessary infrastructure | single database, single application |
| 16 | no claim of tests passing unless executed | see the README "Results": numbers come from executed runs |
| 17 | no claim of a feature that does not exist | limitations listed, [assumptions.md](assumptions.md) |
| 18 | known limitations not hidden | [assumptions.md](assumptions.md) |
| 19 | no real personal data | all synthetic; reserved `.example` e-mail domain |
| 20 | reproducibility and traceability | seeds, manifests, checksums, audit trail, decision traces |

## 9. Definition of Done

| Item | State |
|---|---|
| Business functionality (13 items) | implemented and covered by tests (section 3) |
| Data engineering (9 items): volume, dependencies, unique IDs, valid FKs, reconciled derived values, 7 mandatory anomalies, independent ground truth, reproducibility, CSV / JSON / SQL | implemented and verified (README "Results") |
| Testing and validation (8 items) | executed; results in the README |
| Documentation (all items except the repository) | `README.md`, `docs/*` |
| Docker setup works | built and run during verification (README "Results") |
| **Public GitHub repository accessible** | **not done** - creating and publishing the repository is a manual step for the owner |
| No credentials, secrets or private data in the repository | development defaults only (`nexoraflow_dev`), no tokens; `.env` is git-ignored |

## 10. Forward-deployed-engineer perspective

### Configurability
Approval thresholds, levels and approvers, leave and expense policy limits, budgets, holidays, the number of lines per claim, the split-claim window and the receipt rules are **data or configuration**, not code. Moving the 500 USD boundary means *versioning* the rule rows - end-date the old ones (`effective_to`) and insert new ones with a later `effective_from` - and no Java change or restart; routing and split-claim detection follow the rows, and the validator re-derives every route from the rows that were effective on the claim date. An in-place `UPDATE` of a boundary would silently change how all *historical* claims are judged: tried on the SMALL dataset, moving 500 to 100 made the validator report 53 existing claims as not matching the configured rules (check BI-05), while the versioned change kept all 25 checks green. Adding a rule is just as cheap: one `INSERT` made a 300 USD travel claim require a second approval level.

### Explainability
`GET /api/v1/leave-requests/{id}/explanation` and `GET /api/v1/expense-claims/{id}/explanation` return the rules evaluated, which passed and which failed (with the numbers), the approval path and the chronological event history. The same data is stored in `decision_trace`, so it can be read months later even if policies changed.

### Operational visibility
`/api/v1/reports/{leave,expenses,approvals,financial,exceptions,summary}` calculate every metric from source records. The exception report lists open exceptions (flagged, held, needs review), decisions by reason code and detected anomalies; the validation report lists the records behind any failed check.

### Integration readiness
Stable business identifiers (`EMP-000001`, `EXP-2026-000001`, ...) in every payload and export, OpenAPI documentation, RFC 7807 errors, and clear seams: HR platform (employees, cost centers), payroll (`POST /reimbursements/{id}/process`), accounting (reimbursement and budget reports), identity provider (the `actorEmployeeId` fields are where an authenticated principal would be mapped), notifications (audit events are the natural trigger). None of these integrations is implemented.

### Measurable outcomes
Measured on the generated dataset (these are properties of *synthetic* data and of the rules, not claims about a real company):

| Metric (from `data/reports/operational_metrics.json`) | Value |
|---|---:|
| Requests evaluated (leave + expense) | 2109 (892 leave, 1217 expense) |
| Leave requests approved / rejected / pending | 630 / 108 / 93 |
| Expense claims awaiting approval | 134 |
| Policy violations detected (expense) | 39 claims; missing receipts 15, duplicate receipts 8, suspicious split claims 18; overlapping leave incidents 10; leave requests rejected for insufficient balance 16 |
| Unauthorized approvals prevented | 14 (all recorded in the audit trail; plus 8 out-of-order / overwrite attempts) |
| Duplicate reimbursements prevented | 12 (and 4 premature requests refused) |
| Approval steps / average approval time | 2169 / 37.6 h (simulated, scripted approver behaviour) |
| Multi-level approval frequency | 12.1 % of routed requests |
| Exception frequency | 95 open exceptions (flagged, held, needs review) of 2109 requests |
| Budget utilization | 7.02 % of the allocated budget consumed by approved spend (525,375.49 of 7,481,549.60 USD-equivalent) |
| Reimbursed (eligible / pending / processed) | 525,375.49 / 196,435.44 / 328,940.05 USD-equivalent |

No figure about money saved or time reduced is claimed: nothing here was measured against a real process. If an estimate were needed it would have to be labelled illustrative.

### Questions I would ask the customer before going live
- Which approval tiers, amount boundaries and delegation rules are really in force? (The seeded ones are my reading of the brief.)
- Are leave balances calendar-year or anniversary-year, and what are the real carry-forward and encashment rules?
- How should the system treat approvers on leave: automatic escalation (implemented) or delegation by the approver?
- Which currency conversion source and date should apply to claims?
- Who may correct a cost center or release a flagged claim, and how are these roles authenticated?
- What retention and privacy rules apply to the audit trail?
