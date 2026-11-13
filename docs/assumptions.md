# Assumptions, interpretations and limitations

Everything below is a decision I had to take where the specification left room, or a limit of what was built. They are listed so that nothing is hidden. Rule-level details are in [business-rules.md](business-rules.md); data-level provenance is in [data-provenance.md](data-provenance.md).

## 1. How the specification was interpreted

| # | Topic | Interpretation |
|---|---|---|
| I-1 | **Analysis first vs. build** | The brief ends with "produce the analysis and wait for review". The explicit instruction for this work was to build the project, so the design analysis was written down ([design-analysis.md](design-analysis.md)) and the implementation followed without waiting. |
| I-2 | **Baseline volume** | "400 employees, 800 leave requests, 1,000 expense claims" is the *ordinary* workload. The deliberately defective scenarios are **added on top**: the default (MEDIUM) dataset therefore contains 400 employees, 892 leave requests (800 + 92 scenario requests) and 1,217 expense claims (1,000 + 217 scenario claims), plus 10 submissions that were rejected at intake and have no canonical record (2,119 labelled entities in total). The baseline counts are asserted exactly by a test. |
| I-3 | **Size profiles** | SMALL = 50 employees / 100 leave / 125 claims, MEDIUM (default) = 400 / 800 / 1,000, LARGE = 2,000 / 4,000 / 5,000; the anomaly volume scales 0.15 / 1 / 5. |
| I-4 | **Unit of approval** | The expense **claim** is the unit of evaluation, approval and reimbursement. Lines are validated individually (`VALID` / `FLAGGED` / `INVALID`) but are not approved or paid separately. Partial approval is not modelled. |
| I-5 | **Approval thresholds** | Lower bound exclusive, upper bound inclusive: exactly 500.00 USD stays in the "direct manager" tier, 500.01 USD moves to "manager". Boundaries are rows in `approval_rule` and are read from there everywhere (including split-claim detection). The tiers themselves (500 / 2,000 / 5,000 USD; 10 leave days) are my reading of the brief and are configuration, not code. |
| I-6 | **Business actors** | The five actors (employee, manager, finance approver, operations analyst, system / payroll) are represented by data and by `actorEmployeeId` fields in requests. There is no login. Business authorisation (who may approve which step, no self-approval, no inactive approver) **is** enforced; authentication is not. |
| I-7 | **Libraries** | The brief mentions JavaFaker; it is unmaintained, so its maintained successor **Datafaker** (2.5.4) is used. The base package is `me.niteshh.nexoraflow` (the scaffold's group). Spring Boot **4.0.9** / Java 21 is the version of the project scaffold; all tests ran on it. |
| I-8 | **"Think about AI"** | The brief allows optional AI-assisted features. None were built: every decision is a deterministic rule, which is what makes the system testable and auditable. An LLM could be added later *only* for tasks such as drafting explanations, never for the decision itself. |

## 2. Time, currency and calendars

| # | Assumption |
|---|---|
| T-1 | All business dates are **UTC dates**; timestamps are UTC. The JVM time zone is irrelevant (a run in `Asia/Kolkata` and a run in `UTC` produce identical logical checksums; the Docker image also sets `-Duser.timezone=UTC`). |
| T-2 | The synthetic company's "today" is the **reference date 2026-10-22** (configurable). The simulation clock never runs past 2026-10-22T18:00Z: events scheduled after that moment do not execute, so some requests are genuinely still pending at the end of the dataset. |
| T-3 | Reporting currency is **USD**. Fixed, illustrative rates: EUR 1.09, GBP 1.27, INR 0.012, SGD 0.74, USD 1.00. They are **not market data**; there is no rate feed. |
| T-4 | An employee claims in their **country's currency** (DE: EUR, GB: GBP, IN: INR, SG: SGD, US: USD); other currencies are rejected (`INVALID_CURRENCY`). Policy limits are expressed in that currency; approval thresholds and split-claim boundaries are compared in USD-equivalent. |
| T-5 | Weekends are Saturday and Sunday in every country. Public holidays are per country and year (calendars for 2025, 2026 and 2027 are seeded; they are illustrative, not an official calendar). Half-days are not modelled, and neither are employee-specific working calendars (the brief makes them optional: "if supported"). |

## 3. Leave

| # | Assumption / limitation |
|---|---|
| L-1 | Leave is counted in **business days**, never "end - start + 1". `requested_days` stores what the submitter wrote (calendar days); `calculated_business_days` is the authoritative number. |
| L-2 | A request must stay inside **one calendar year** (LV-04). A request crossing New Year must be split by the requester. |
| L-3 | Balances are per employee, policy and **calendar year**. There is no automated **year-end rollover job**: balances are provisioned when an employee is created and by the generator (including a carry-forward amount into 2026). The carry-forward *rule* (limited by the policy) is implemented; the scheduled job is not. |
| L-4 | `ANNUAL_UPFRONT` policies grant the full (pro-rated by remaining months for mid-year joiners) entitlement at provisioning; `MONTHLY` policies accrue one instalment per month with cumulative rounding so the twelve instalments sum to the annual entitlement exactly. Accrual is idempotent per balance and month. |
| L-5 | Days are **reserved** when a request enters approval and **consumed** at final approval; rejected, flagged or cancelled requests release the reservation; cancelling an approved request restores the days. |
| L-6 | The maximum-consecutive-days rule (LV-07) looks only at the requested range; adjacent approved leave is not added to it. |
| L-7 | Overlap (LV-09) is checked against **approved** leave only (as the brief specifies). Two overlapping *pending* requests can coexist; the second one to reach final approval is blocked and flagged. |
| L-8 | Part-time employees are not pro-rated; a policy is simply eligible or not for an employment type. Contractors have no leave policy. |
| L-9 | Only the requester can cancel. Approved leave can be cancelled until its start date (the used days are restored); once it has started it can no longer be cancelled. An approved expense claim cannot be cancelled (it is terminal). |
| L-10 | `requires_manager_approval` on an expense policy is informational; approval routing is driven only by `approval_rule`. |

## 4. Expense

| # | Assumption / limitation |
|---|---|
| E-1 | **Terminated employees may still claim** expenses incurred during their employment (EX-01 blocks only `INACTIVE` employees; EX-07 rejects transactions outside the employment period). |
| E-2 | Claims are charged to the budget of the **month of the claim (booking) date**, not of the transaction date. Budgets are monthly per cost center. |
| E-3 | Budget position is derived from source claims (`allocated - approved - pending commitments`). Pending commitments count only if `commit_pending` is true. A claim larger than the remaining budget is **held** (`ON_HOLD`), not rejected. |
| E-4 | Daily limits are per employee, category and transaction day; monthly limits per employee, category and calendar month. Only claims `PENDING_APPROVAL` or `APPROVED` count as committed spend. |
| E-5 | Receipts are **metadata only** (type, size, status, SHA-256 hash). There is no document storage and no OCR. Receipt hashes in the synthetic data are SHA-256 digests of unique synthetic tokens. |
| E-6 | **Split-claim detection is a heuristic.** It needs the same employee, cost center, category and normalised merchant, dates **chained** (each claim within 3 days of the next one, followed transitively), at least two claims each at or below an approval boundary and together above it. A long series of small purchases at one merchant, each within three days of the previous one, can therefore add up to a finding. Different merchant spellings or different merchants for one purchase are **not** linked; a legitimate series of purchases can be flagged (outcome `FLAGGED` for review, never an automatic rejection). |
| E-7 | A rejected, cancelled or approved claim is final. A corrected claim is a **new** claim; there is no re-open. |
| E-8 | Expense categories: `TRAVEL`, `MEALS`, `LODGING`, `OFFICE_SUPPLIES`, `TRAINING`, `CLIENT_ENTERTAINMENT`. Any other category is rejected at intake (`UNSUPPORTED_CATEGORY`). |

## 5. Approval and reimbursement

| # | Assumption / limitation |
|---|---|
| A-1 | The "manager" of a request is the nearest available person in the reporting line with job level <= 3, the "department head" job level <= 2; finance approvers are located by designation ("Finance Manager", "VP Finance"), preferring the requester's country. |
| A-2 | When the natural approver is unavailable the route **escalates up the reporting line**; if nobody qualifies the request goes to `NEEDS_REVIEW` (`NO_VALID_APPROVER`). |
| A-3 | If one person fills two levels of a route the later step is `SKIPPED` (nobody approves twice). |
| A-4 | In the **generated** dataset approvers' decisions are scripted by the planner (step comment `scripted approver decision`), because the data has to contain a controlled mix of approvals, rejections and pending requests. The *checks* on those decisions (authority, order, self-approval) are the real service code. |
| A-4a | The **cost center does not influence the approval route.** Routing uses request type, amount, category, country, department, the requester's reporting line and the configured rules; the cost center matters for validity (EX-15), authorisation (EX-16) and the budget (EX-17). The brief's suggested `ApprovalRule` fields contain no cost-center scope, and a cost-center-specific rule would be a new nullable column plus one filter in `ApprovalRuleResolver`. |
| A-5 | Reimbursement is simulated: a reimbursement is created after final approval and "paid" by an explicit call that stands for the payroll run. There is no bank or payroll integration, no partial payment and no payment in a currency other than the claim currency. |

## 6. Synthetic data

| # | Assumption / limitation |
|---|---|
| D-1 | **No real personal data.** Names, phone numbers and cities come from Datafaker with a locale per country; e-mail addresses use the reserved, non-routable domain `nexora-technologies.example`; the company is fictional. |
| D-2 | **Reproducibility contract.** The same seed + configuration + application version + business-rules version + Datafaker version (and a JDK that honours the `java.util.Random` specification, which every JDK does) produce the same **logical** dataset: the SHA-256 checksum of each table over its logical content is identical. Wall-clock metadata (`generatedAtWallClock`, `generation_run`) is excluded. |
| D-3 | **Scenario subjects are reserved.** Employees used by anomaly scenarios (every 4th eligible individual contributor, the direct reports of deliberately unavailable managers, and the CEO for the hierarchy scenario) never appear in the ordinary workload. The baseline therefore cannot create accidental overlaps, duplicates or split claims with scenario records, which keeps the ground truth unambiguous. Between scenarios, an employee whose leave balance a scenario exhausted is not reused, and an employee carries at most one big-ticket scenario per expense category and month, because the monthly limits are finite (both rules were added after the LARGE profile exposed the collisions, see [design-analysis.md](design-analysis.md), section 7). |
| D-4 | **The 100 % result is a consistency result.** The ground truth is an independently written answer key (own restatement of the policy, shadow ledgers, own approval oracle) produced by the same author for scenarios the same author designed. Agreement proves the engines follow the *documented* rules and that the documented scenarios are detected; it does not prove that the rules are right for a real company, and it says nothing about anomaly types that were not designed. |
| D-5 | Anomaly prevalence (247 anomalous entities out of 2,119 labelled, about 11.7 %) is much higher than in real life so that every scenario has enough examples. Precision and recall on this dataset must not be read as real-world performance. |
| D-6 | Invalid submissions (unknown employee, unknown receipt, unknown cost center, inverted dates, unsupported category) are intentional and preserved; the data-quality check DQ-08 reports them as *informational*, not as failures. |

## 7. Technical limitations

| # | Limitation |
|---|---|
| X-1 | No authentication, authorisation layer, rate limiting or tenant isolation. Do not expose the application to an untrusted network as is. |
| X-2 | Default database credentials (`nexoraflow` / `nexoraflow_dev`) are for local development only; override them with `NEXORA_DB_*` for anything else. |
| X-3 | The generator **resets the database** (`TRUNCATE`) when generating. It is protected by `nexoraflow.generator.allow-reset` (set it to `false` to forbid), but there is no other safeguard: never point it at a database you care about. |
| X-4 | Only one generation run can be active at a time (a second request returns `409`). |
| X-5 | The workflow is synchronous; there are no notifications, queues or scheduled jobs (accruals and payroll runs are driven by the simulation or by explicit calls). |
| X-6 | The application can run as several instances (IDs and locks are database-based), but no load or soak testing was done. Measured generation times are in the README. |
| X-7 | List endpoints use Spring's `page`, `size` and `sort` parameters and the filters documented in [api-documentation.md](api-documentation.md); there is no full-text search. |
| X-8 | The public GitHub repository required by the Definition of Done is a **manual step**: no repository was created or pushed by this work. |
