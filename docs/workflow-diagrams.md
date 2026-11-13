# Workflow diagrams

All diagrams are [Mermaid](https://mermaid.js.org/) and render on GitHub. The state diagrams mirror the explicit state machines in the code (`LeaveRequestStatus.LIFECYCLE`, `ExpenseClaimStatus.LIFECYCLE`, `ApprovalStatus.LIFECYCLE`, `ReimbursementStatus.LIFECYCLE`); any transition that is not drawn is rejected, audited as `INVALID_TRANSITION_REJECTED` and changes nothing.

## 1. Leave request lifecycle

```mermaid
stateDiagram-v2
    [*] --> SUBMITTED : submit (intake + audit)
    SUBMITTED --> PENDING_APPROVAL : evaluated, all rules pass - (days reserved, route created)
    SUBMITTED --> REJECTED : HARD rule failed - (invalid, no balance, no notice, ...)
    SUBMITTED --> FLAGGED : overlap with approved leave (LV-09)
    SUBMITTED --> NEEDS_REVIEW : documentation missing (LV-10) - or no approver (LV-11)
    SUBMITTED --> CANCELLED : requester cancels
    FLAGGED --> PENDING_APPROVAL : analyst releases
    FLAGGED --> REJECTED : analyst rejects
    FLAGGED --> CANCELLED
    NEEDS_REVIEW --> PENDING_APPROVAL : analyst releases
    NEEDS_REVIEW --> REJECTED : analyst rejects
    NEEDS_REVIEW --> CANCELLED
    PENDING_APPROVAL --> APPROVED : final approval - (re-check overlap, consume days once)
    PENDING_APPROVAL --> REJECTED : an approver rejects - (reservation released)
    PENDING_APPROVAL --> FLAGGED : final approval blocked by overlap
    PENDING_APPROVAL --> CANCELLED : requester cancels - (reservation released)
    APPROVED --> CANCELLED : requester cancels - (used days restored)
    REJECTED --> [*]
    CANCELLED --> [*]
```

## 2. Expense claim lifecycle

```mermaid
stateDiagram-v2
    [*] --> SUBMITTED : submit (staged exactly as received)
    SUBMITTED --> PENDING_APPROVAL : evaluated, all rules pass - (route created)
    SUBMITTED --> REJECTED : HARD rule failed - (limit, currency, dates, policy, ...)
    SUBMITTED --> ON_HOLD : cost center invalid (EX-15) - or budget exhausted (EX-17)
    SUBMITTED --> FLAGGED : receipt / duplicate / split / - cost-center authorisation (EX-12..14, 16, 18)
    SUBMITTED --> NEEDS_REVIEW : no approver (EX-19)
    SUBMITTED --> CANCELLED
    ON_HOLD --> PENDING_APPROVAL : cost center corrected / analyst releases
    ON_HOLD --> REJECTED
    ON_HOLD --> CANCELLED
    FLAGGED --> PENDING_APPROVAL : analyst releases
    FLAGGED --> REJECTED : analyst rejects
    FLAGGED --> CANCELLED
    NEEDS_REVIEW --> PENDING_APPROVAL
    NEEDS_REVIEW --> REJECTED
    NEEDS_REVIEW --> CANCELLED
    PENDING_APPROVAL --> APPROVED : all approval steps approved - (eligibility = ELIGIBLE)
    PENDING_APPROVAL --> REJECTED : an approver rejects
    PENDING_APPROVAL --> FLAGGED : split pattern completed by a later claim
    PENDING_APPROVAL --> ON_HOLD
    PENDING_APPROVAL --> CANCELLED
    APPROVED --> [*]
    REJECTED --> [*]
    CANCELLED --> [*]
```

## 3. Approval step lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING : first step of a route
    [*] --> WAITING : later steps
    [*] --> SKIPPED : same approver already covers an earlier level
    WAITING --> PENDING : the previous step was approved
    WAITING --> CANCELLED : request rejected, flagged or withdrawn
    PENDING --> APPROVED : approver approves
    PENDING --> REJECTED : approver rejects - (remaining steps CANCELLED)
    PENDING --> ESCALATED : approver escalates to their manager - (replacement step created)
    PENDING --> CANCELLED : request rejected, flagged or withdrawn
    APPROVED --> [*]
    REJECTED --> [*]
    ESCALATED --> [*]
    CANCELLED --> [*]
    SKIPPED --> [*]
```

Terminal steps are immutable - enforced by the application **and** by the `trg_approval_step_guard` trigger.

## 4. Reimbursement lifecycle

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT : request accepted - (claim APPROVED + ELIGIBLE, - all steps completed, none exists yet)
    PENDING_PAYMENT --> PAID : payroll run (payment reference assigned)
    PENDING_PAYMENT --> FAILED
    PENDING_PAYMENT --> CANCELLED
    FAILED --> PENDING_PAYMENT : retry
    FAILED --> CANCELLED
    PAID --> [*]
    CANCELLED --> [*]
```

Requests that do **not** create a reimbursement are not lifecycle states; they are recorded as `reimbursement_attempt` rows (`DUPLICATE_REJECTED`, `NOT_ELIGIBLE_REJECTED`).

## 5. Leave: from submission to balance consumption

```mermaid
flowchart TD
    A[POST /leave-requests] --> B{Employee and policy exist?}
    B -- no --> B1[Preserve in submission_record<br/>REJECTED_AT_INTAKE, HTTP 422]
    B -- yes --> C[Create request SUBMITTED<br/>audit REQUEST_SUBMITTED]
    C --> D[POST /evaluate: rules LV-01..LV-10]
    D --> E{Strongest failed rule}
    E -- HARD --> R[REJECTED]
    E -- FLAG --> F[FLAGGED]
    E -- REVIEW --> N[NEEDS_REVIEW]
    E -- none --> G[Plan approval route<br/>LV-11 approver resolvable?]
    G -- no --> N
    G -- yes --> H[Reserve days PENDING_RESERVE<br/>create steps, first step PENDING<br/>PENDING_APPROVAL]
    H --> I[Approver approves step n]
    I --> J{More steps?}
    J -- yes --> K[Next step becomes PENDING] --> I
    J -- no --> L{Overlap with leave approved meanwhile?}
    L -- yes --> M[Release reservation<br/>FLAGGED, FINAL_APPROVAL_BLOCKED]
    L -- no --> O[CONSUME: pending to used exactly once<br/>APPROVED]
    I -- rejects --> P[Release reservation<br/>remaining steps CANCELLED<br/>REJECTED]
    F --> Q[Analyst review: release or reject]
    N --> Q
    Q -- release --> H
    Q -- reject --> R
```

## 6. Expense: from submission to reimbursement

```mermaid
flowchart TD
    A[POST /expense-claims] --> S[Store submission_record exactly as received]
    S --> B{Employee known and<br/>category supported?}
    B -- no --> B1[REJECTED_AT_INTAKE, HTTP 422]
    B -- yes --> C[Create claim + lines SUBMITTED<br/>unknown cost center / receipt kept in submitted_*_ref,<br/>canonical FK = NULL]
    C --> D[POST /evaluate: rules EX-01..EX-18]
    D --> E{Strongest failed rule}
    E -- HARD --> R[REJECTED]
    E -- HOLD --> H[ON_HOLD<br/>cost center / budget]
    E -- FLAG --> F[FLAGGED]
    E -- none --> G[Plan route by USD total<br/>EX-19 approver resolvable?]
    G -- no --> N[NEEDS_REVIEW]
    G -- yes --> P[PENDING_APPROVAL<br/>route created]
    H -- PATCH cost-center / analyst release --> D
    F -- analyst release --> P
    N -- analyst release --> P
    P --> Q{All steps approved?}
    Q -- an approver rejects --> R
    Q -- yes --> BC{Budget still covers the claim?}
    BC -- no --> BH[ON_HOLD, FINAL_APPROVAL_BLOCKED]
    BC -- yes --> AP[APPROVED + ELIGIBLE]
    AP --> RB[Reimbursement requested automatically<br/>at final approval; POST /reimbursements<br/>runs the same idempotent check]
    RB --> X{Already reimbursed?}
    X -- yes --> X1[DUPLICATE_REJECTED<br/>attempt recorded, HTTP 409]
    X -- no --> Y{Approved, eligible,<br/>all steps completed?}
    Y -- no --> Y1[NOT_ELIGIBLE_REJECTED<br/>attempt recorded]
    Y -- yes --> Z[Reimbursement PENDING_PAYMENT<br/>one per claim]
    Z --> PAY[POST /reimbursements/id/process<br/>PAID + payment reference]
```

## 7. Approval route selection

```mermaid
flowchart TD
    A[Request: type, requester, amount, date] --> B[Load ACTIVE approval_rule rows<br/>of the workflow type]
    B --> C[Keep rules effective on the date,<br/>matching country / department / category,<br/>min < amount <= max]
    C --> D[Group by approval level]
    D --> E[Per level pick one rule:<br/>lowest priority, then most specific, then lowest id]
    E --> F[For each level resolve the approver<br/>from the reporting line or by designation]
    F --> G{Approver available?}
    G -- yes --> H[Planned step]
    G -- no, walk up the line --> I[Escalate to next qualifying manager<br/>audit APPROVER_ESCALATED_UNAVAILABLE]
    I --> G
    G -- nobody qualifies --> X[Route unresolvable: NO_VALID_APPROVER<br/>request NEEDS_REVIEW]
    H --> J{Same person as an earlier level?}
    J -- yes --> K[Step SKIPPED, no second approval]
    J -- no --> L[Step WAITING; the first one PENDING]
```

## 8. Leave balance ledger

```mermaid
flowchart LR
    G[OPENING_GRANT / ACCRUAL / CARRY_FORWARD] -->|available +| B((balance))
    B -->|PENDING_RESERVE: pending +, available -| P[reserved]
    P -->|CONSUME at final approval: pending -, used +<br/>available unchanged| U[used]
    P -->|PENDING_RELEASE on reject / cancel / flag:<br/>pending -, available +| B
    U -->|RESTORE on cancel of approved:<br/>used -, available +| B
```

`available = opening + accrued + carried forward - used - pending` is a generated column; the ledger rows are immutable and reconcile with the balance (validation check BI-02).

## 9. Data generation, validation and evaluation

```mermaid
flowchart TD
    SEED[seed + size profile + reference date] --> MS[Master data<br/>org chart, policies, rules, budgets]
    MS --> PL[Planners: ordinary + anomaly scenarios]
    PL --> GT[(ground_truth.label<br/>independent oracle)]
    PL --> SIM[Discrete-event simulation<br/>simulated clock]
    SIM -->|real services only| ENG[Leave / expense / approval / reimbursement engines]
    ENG --> DB[(canonical tables + audit + traces)]
    DB --> VAL[Validation module<br/>25 checks, SQL + independent re-derivation]
    DB --> EVAL[Evaluation module]
    GT --> EVAL
    VAL --> REP[Reports: validation, evaluation,<br/>operational metrics]
    EVAL --> REP
    DB --> EXP[Export: CSV, JSON, SQL seed,<br/>manifest + logical checksums]
```

The business engines never read `ground_truth`; only the evaluation module compares engine results with it. The simulation drives the *real* services (it does not insert rows directly), which is why the generated audit trail, balances and approval histories are genuine consequences of the business rules.
