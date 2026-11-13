# API documentation

REST API of NexoraFlow, version `1.0.0`. The machine-readable contract is generated from the code (springdoc-openapi), so it cannot drift from the implementation:

| | URL (default port 8080) |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI 3 document (JSON) | <http://localhost:8080/v3/api-docs> |
| Health | <http://localhost:8080/actuator/health> |

This page explains the conventions and the workflows, lists every endpoint, and shows **real requests and responses** captured from a running instance on the default (MEDIUM) dataset (section 5). Rule IDs (`LV-..`, `EX-..`, `AP-..`, `RB-..`) refer to [business-rules.md](business-rules.md).

## 1. Conventions

| Topic | Convention |
|---|---|
| Base path | `/api/v1` |
| Format | JSON (`application/json`), UTF-8; fields in `camelCase` |
| Identifiers | stable business IDs: `EMP-000001`, `LVE-2026-000001`, `EXP-2026-000001`, `APR-EXP-2026-000001-01`, `RMB-2026-000001`, ... |
| Dates / times | dates `YYYY-MM-DD` (UTC business dates), timestamps ISO-8601 UTC (`2026-10-20T10:00:00Z`) |
| Money | JSON numbers with two decimals, `BigDecimal` on the server (never floating point); currency as ISO code next to the amount |
| Enumerations | upper-case strings (`PENDING_APPROVAL`, `MANAGER`, ...) |
| Pagination | list endpoints take `page` (0-based), `size` and `sort` (e.g. `sort=employeeId,desc`); the response is `{ "content": [...], "page", "size", "totalElements", "totalPages" }` |
| Actors | there is no login. Operations that someone *performs* carry the acting employee in the body (`actorEmployeeId`, `requestedBy`, `actor`) and the service enforces what that person may do (no self-approval, only the assigned active approver, ...). In production an authenticated principal would be mapped to this field. |
| Entities | JPA entities are never exposed; every response is a DTO |
| Idempotency | `POST /reimbursements` is idempotent (the reimbursement already exists after the final approval, so the request is a recorded `409`); other `POST`s create new records |

### Error format (RFC 7807)

Every error has the same shape; stack traces, SQL and class names are logged on the server and never returned.

```json
{
  "detail": "Requester EMP-000086 cannot approve their own request",
  "instance": "/api/v1/approvals/APR-LVE-2026-000895-01/approve",
  "status": 403,
  "title": "Forbidden",
  "type": "https://nexoraflow.example/problems/forbidden",
  "timestamp": "2026-11-12T19:08:57.138773119Z",
  "reasonCode": "SELF_APPROVAL_NOT_ALLOWED"
}
```

| Status | When |
|---|---|
| `400` | malformed or invalid request (`errors` lists `{field, message}`), invalid parameter value |
| `403` | `SELF_APPROVAL_NOT_ALLOWED`, `UNAUTHORIZED_APPROVER`, `APPROVER_INACTIVE` |
| `404` | the resource does not exist |
| `409` | `INVALID_STATE_TRANSITION`, `INVALID_STEP_ORDER`, `STEP_ALREADY_COMPLETED`, `DUPLICATE_REIMBURSEMENT`, concurrent modification, data-integrity conflict, a generation run already in progress |
| `422` | business rule refusal (e.g. `CLAIM_NOT_REIMBURSABLE`) and **intake rejection**: the body carries `submissionId` and `errors`; the submission is preserved in staging |
| `500` | unexpected error (generic message) |

Note that a request *rejected by the rules* (for example insufficient leave balance) is **not** an HTTP error: the submission succeeds (`201`), and the evaluation sets the request to `REJECTED` with `decisionReason`.

## 2. Workflows through the API

### Leave

```
POST /leave-requests[?evaluate=true]      -> 201 SUBMITTED (or already evaluated)
POST /leave-requests/{id}/evaluate        -> PENDING_APPROVAL | REJECTED | FLAGGED | NEEDS_REVIEW
GET  /approvals/pending?approverId=...    -> the approver's queue
POST /approvals/{stepId}/approve          -> next step, or APPROVED after the last one (balance consumed once)
POST /approvals/{stepId}/reject           -> REJECTED, remaining steps cancelled, reservation released
POST /leave-requests/{id}/cancel          -> CANCELLED (requester only; approved leave restores the days)
POST /leave-requests/{id}/review          -> analyst releases or rejects a FLAGGED / NEEDS_REVIEW request
GET  /leave-requests/{id}/explanation     -> rules passed / failed, approval path, events
```

### Expense and reimbursement

```
POST  /expense-claims[?evaluate=true]     -> 201 SUBMITTED (1..10 lines, optional inline receipt metadata)
POST  /expense-claims/{id}/evaluate       -> PENDING_APPROVAL | REJECTED | FLAGGED | ON_HOLD | NEEDS_REVIEW
PATCH /expense-claims/{id}/cost-center    -> correct a missing / invalid cost center of a held claim
POST  /approvals/{stepId}/approve         -> level by level; after the last: budget re-check, APPROVED + ELIGIBLE and the
                                             reimbursement is created automatically (PENDING_PAYMENT)
GET   /reimbursements?employeeId=...      -> find it
POST  /reimbursements                     -> idempotent re-request: 409 duplicate in the normal case | 422 not eligible | 201 only
                                             if an approved claim somehow has none (every attempt is recorded)
POST  /reimbursements/{id}/process        -> PAID with a payment reference (simulated payroll run)
GET   /expense-claims/{id}/explanation    -> rules passed / failed, approval path, events
```

## 3. Endpoint reference

`{id}` is a business ID. All list endpoints accept `page`, `size`, `sort`.

### Employees

| Method and path | Purpose | Parameters / body |
|---|---|---|
| `GET /employees` | list employees | `departmentId`, `country`, `status` (`ACTIVE`/`INACTIVE`/`TERMINATED`), `managerId` |
| `GET /employees/{id}` | one employee | - |
| `POST /employees` | create an employee; leave policies and balances are provisioned automatically | `firstName`, `lastName`, `email`, `departmentId`, `designation`, `jobLevel` (1-5), `managerId`, `country`, `employmentType`, `joiningDate`, `costCenterId`, ... -> `201` |
| `GET /employees/{id}/leave-balances` | balances per policy and year (`availableBalance` derived) | - |

### Leave

| Method and path | Purpose | Parameters / body |
|---|---|---|
| `POST /leave-requests` | submit | `employeeId`, `leavePolicyId`, `startDate`, `endDate`, optional `requestedDays`, `reason`, `supportingDocumentRef`; query `evaluate=true` to evaluate at once -> `201`; unknown employee/policy -> `422` with `submissionId` |
| `GET /leave-requests` | list | `employeeId`, `status`, `leavePolicyId`, `startFrom`, `startTo` |
| `GET /leave-requests/{id}` | one request | - |
| `POST /leave-requests/{id}/evaluate` | evaluate a `SUBMITTED` request with LV-01..LV-11 | `{ "actorEmployeeId", "reason" }` |
| `POST /leave-requests/{id}/cancel` | cancel (requester only) | `{ "actorEmployeeId", "reason" }` |
| `POST /leave-requests/{id}/review` | analyst resolves `NEEDS_REVIEW` / `FLAGGED` | `{ "actorEmployeeId", "action": "RELEASE" or "REJECT", "comment" }` |
| `GET /leave-requests/{id}/explanation` | why was this decision taken? | - |
| `GET /leave-requests/{id}/events` | chronological audit events | - |

### Expenses

| Method and path | Purpose | Parameters / body |
|---|---|---|
| `POST /expense-claims` | submit a claim with 1..10 lines | `employeeId`, `expensePeriodStart`, `expensePeriodEnd`, `costCenterId`, `currency`, `lines[]` (`category`, `merchantName`, `transactionDate`, `description`, `amount`, optional `currency`, `receiptId` or inline `receipt` {`fileName`, `fileType`, `fileSizeBytes`, `receiptHash`}); query `evaluate=true` |
| `GET /expense-claims` | list | `employeeId`, `status`, `costCenterId`, `claimFrom`, `claimTo` |
| `GET /expense-claims/{id}` | one claim with its lines | - |
| `POST /expense-claims/{id}/evaluate` | evaluate with EX-01..EX-19 | `{ "actorEmployeeId", "reason" }` |
| `POST /expense-claims/{id}/cancel` | cancel (requester only, not after approval) | `{ "actorEmployeeId", "reason" }` |
| `POST /expense-claims/{id}/review` | analyst releases or rejects a `NEEDS_REVIEW` / `FLAGGED` / `ON_HOLD` claim | `{ "actorEmployeeId", "action", "comment" }` |
| `PATCH /expense-claims/{id}/cost-center` | correct a missing / invalid cost center (the submitted value is preserved) | `{ "actorEmployeeId", "costCenterId" }` |
| `GET /expense-claims/{id}/explanation` | decision explanation | - |
| `GET /expense-claims/{id}/events` | audit events | - |

### Approvals

| Method and path | Purpose | Parameters / body |
|---|---|---|
| `GET /approvals/pending` | steps awaiting action | `approverId`, `requestType` (`LEAVE`/`EXPENSE`) |
| `GET /approvals/{id}` | one step | - |
| `POST /approvals/{id}/approve` | approve a step | `{ "actorEmployeeId", "comments", "reason" }` |
| `POST /approvals/{id}/reject` | reject; the request is rejected and no further step runs | same |
| `POST /approvals/{id}/escalate` | hand the step to the approver's manager; returns the replacement step | same |
| `GET /approval-rules` | the configured routing rules (thresholds live here) | - |

### Reimbursements

| Method and path | Purpose | Parameters / body |
|---|---|---|
| `POST /reimbursements` | (re-)request the reimbursement of an approved claim. The final approval already creates it, so this is idempotent: `409 DUPLICATE_REIMBURSEMENT` normally, `422 CLAIM_NOT_REIMBURSABLE` if the claim is not approved / eligible, `201` only if none exists; every attempt is recorded | `{ "expenseClaimId", "requestedBy" }` |
| `GET /reimbursements` | list | `employeeId`, `status` |
| `GET /reimbursements/{id}` | one reimbursement | - |
| `POST /reimbursements/{id}/process` | mark as paid (simulated payroll run) | `{ "actor" }` |

### Policies and configuration

| Method and path | Purpose | Parameters |
|---|---|---|
| `GET /leave-policies` | leave policies | `country`, `leaveType`, `status` |
| `GET /expense-policies` | expense policies (all effective-dated versions) | `country`, `category`, `status` |
| `GET /cost-centers` | cost centers | `departmentId`, `country`, `status` |
| `GET /cost-centers/{id}` | one cost center | - |
| `GET /cost-centers/{id}/budget` | budget position derived from source claims | `date` (default: today) |

### Audit

| Method and path | Purpose | Parameters |
|---|---|---|
| `GET /audit-events` | search the append-only audit trail | `entityType`, `entityId`, `eventType`, `category`, `actor`, `from`, `to` (ISO date-time) |

### Reporting (all metrics are calculated from source records)

| Method and path | Content |
|---|---|
| `GET /reports/leave` | volumes by status, insufficient-balance count, overlap incidents, utilisation by department, average approval time |
| `GET /reports/expenses` | claim counts and USD amounts by status, policy violations, missing / duplicate receipts, split claims, distribution by category, budget utilisation by cost center |
| `GET /reports/approvals` | steps, pending, rejected, average approval time, multi-level frequency, unauthorised attempts by reason, escalations |
| `GET /reports/financial` | reimbursement amounts (eligible, pending, processed), duplicate attempts, budget consumption |
| `GET /reports/exceptions` | open exceptions, decisions by reason code, detected anomalies, preserved invalid submissions |
| `GET /reports/summary` | all of the above in one document |

### Generation, validation and evaluation

| Method and path | Purpose | Parameters / body |
|---|---|---|
| `POST /generator/run` | run a pipeline command in the background -> `202` | `{ "command", "size", "seed", "referenceDate", "async" }`; commands: `generate-all`, `generate-master`, `generate-leave`, `generate-expense`, `generate-anomalies`, `validate`, `evaluate`, `export`, `reproducibility-check`, `full-pipeline`, `reset`. The generating commands **reset the database** (refused when `nexoraflow.generator.allow-reset=false`); a second run while one is active -> `409` |
| `GET /generator/status` | state, stage and result of the current / last run | - |
| `POST /validation/run` | run all 25 checks and store the report | - |
| `GET /validation/report` | latest structured report: which check failed, for which records | - |
| `POST /evaluation/run` | compare engine decisions with the ground truth and store the report | - |
| `GET /evaluation/report` | latest report: precision / recall / F1, decision accuracy, mismatches | - |

## 4. Reason codes and error semantics

The `reasonCode` of an error and the `decisionReason` of a request use the same stable vocabulary; the complete catalogue with its meaning is in [business-rules.md](business-rules.md#6-reason-codes). Two rules of thumb when integrating:

- *A business refusal of a request* (insufficient balance, limit exceeded, missing receipt, ...) is part of the **resource** (`requestStatus` / `claimStatus`, `decisionReason`), not an HTTP error. Poll or read the resource, or call `GET .../explanation`.
- *A refusal of an action* (approving your own request, approving out of order, paying a claim twice) is an **HTTP error** (`403` / `409` / `422`) with a `reasonCode`, and it is recorded in the audit trail (`GET /audit-events?category=VIOLATION`).

## 5. Real requests and responses

Captured on 2026-11-13 from a running instance on the default (MEDIUM) dataset; the actors are real employees of that dataset (requester `EMP-000086`, manager `EMP-000043`). Long responses are abridged and marked. IDs and timestamps will differ on your instance.

### Reference data

#### Get an employee

```http
GET /api/v1/employees/EMP-000086
```

Response `200 OK`

```json
{
  "employeeId": "EMP-000086",
  "firstName": "Isreal",
  "lastName": "Blanda",
  "email": "isreal.blanda@nexora-technologies.example",
  "phone": "(983) 780-5989",
  "departmentId": "DEP-ENG",
  "designation": "DevOps Engineer",
  "jobLevel": 5,
  "managerId": "EMP-000043",
  "country": "US",
  "location": "San Francisco",
  "employmentType": "FULL_TIME",
  "employmentStatus": "ACTIVE",
  "joiningDate": "2023-08-21",
  "costCenterId": "CC-0002"
}
```

#### Leave balances of the employee

```http
GET /api/v1/employees/EMP-000086/leave-balances
```

Response `200 OK`

```json
[
  {
    "leaveBalanceId": "LBAL-000171",
    "employeeId": "EMP-000086",
    "leavePolicyId": "LPOL-ANNUAL-US",
    "year": 2026,
    "openingBalance": 15.0,
    "accruedBalance": 0.0,
    "carriedForwardBalance": 4.0,
    "usedBalance": 2.0,
    "pendingBalance": 3.0,
    "availableBalance": 14.0,
    "lastUpdatedAt": "2026-03-13T13:53:00Z"
  },
  {
    "leaveBalanceId": "LBAL-000172",
    "employeeId": "EMP-000086",
    "leavePolicyId": "LPOL-SICK",
    "year": 2026,
    "openingBalance": 10.0,
    "accruedBalance": 0.0,
    "carriedForwardBalance": 0.0,
    "usedBalance": 0.0,
    "pendingBalance": 0.0,
    "availableBalance": 10.0,
    "lastUpdatedAt": "2026-02-18T17:34:00Z"
  }
]
```

#### Approval rules: the thresholds are data

```http
GET /api/v1/approval-rules
```

Response `200 OK` (abridged)

```json
{
  "content": [
    {
      "approvalRuleId": "AR-EX-001",
      "workflowType": "EXPENSE",
      "minimumAmount": 0.0,
      "maximumAmount": 500.0,
      "amountUnit": "USD",
      "approvalLevel": 1,
      "approverRole": "DIRECT_MANAGER",
      "effectiveFrom": "2025-01-01",
      "priority": 100,
      "ruleStatus": "ACTIVE",
      "description": "Up to 500 USD: direct manager"
    },
    {
      "approvalRuleId": "AR-EX-002",
      "workflowType": "EXPENSE",
      "minimumAmount": 500.0,
      "maximumAmount": 2000.0,
      "amountUnit": "USD",
      "approvalLevel": 1,
      "approverRole": "MANAGER",
      "effectiveFrom": "2025-01-01",
      "priority": 100,
      "ruleStatus": "ACTIVE",
      "description": "500-2,000 USD: manager"
    },
    {
      "approvalRuleId": "AR-EX-003",
      "workflowType": "EXPENSE",
      "minimumAmount": 2000.0,
      "maximumAmount": 5000.0,
      "amountUnit": "USD",
      "approvalLevel": 1,
      "approverRole": "MANAGER",
      "effectiveFrom": "2025-01-01",
      "priority": 100,
      "ruleStatus": "ACTIVE",
      "description": "2,000-5,000 USD: manager ..."
    },
    "... 9 more"
  ],
  "page": 0,
  "size": 50,
  "totalElements": 12,
  "totalPages": 1
}
```

### Leave

#### Submit and evaluate a leave request (5 business days)

`evaluate=true` submits and evaluates in one call. The request is valid, so it enters the approval workflow and the 5 days are reserved.

```http
POST /api/v1/leave-requests?evaluate=true
Content-Type: application/json

{
  "employeeId": "EMP-000086",
  "leavePolicyId": "LPOL-ANNUAL-US",
  "startDate": "2026-12-07",
  "endDate": "2026-12-11",
  "reason": "Family holiday"
}
```

Response `201 Created`

```json
{
  "leaveRequestId": "LVE-2026-000893",
  "employeeId": "EMP-000086",
  "leavePolicyId": "LPOL-ANNUAL-US",
  "startDate": "2026-12-07",
  "endDate": "2026-12-11",
  "requestedDays": 5.0,
  "calculatedBusinessDays": 5.0,
  "reason": "Family holiday",
  "submissionDate": "2026-11-12",
  "requestStatus": "PENDING_APPROVAL",
  "decision": "PENDING_APPROVAL",
  "decisionReason": "",
  "createdAt": "2026-11-12T20:11:34.337741Z",
  "updatedAt": "2026-11-12T20:11:34.594160Z"
}
```

#### Why was this decision taken?

The explanation lists every rule that was evaluated, with the numbers behind it (the rule list is cut to the first entries here).

```http
GET /api/v1/leave-requests/LVE-2026-000893/explanation
```

Response `200 OK` (abridged)

```json
{
  "requestType": "LEAVE",
  "requestId": "LVE-2026-000893",
  "currentStatus": "PENDING_APPROVAL",
  "decision": "PENDING_APPROVAL",
  "reasonCodes": [],
  "businessRulesVersion": "BR-1.0.0",
  "evaluation": {
    "input": {
      "endDate": "2026-12-11",
      "startDate": "2026-12-07",
      "employeeId": "EMP-000086",
      "leavePolicyId": "LPOL-ANNUAL-US",
      "requestedDays": 5.0,
      "submissionDate": "2026-11-12"
    },
    "decision": "PENDING_APPROVAL",
    "requestId": "LVE-2026-000893",
    "reasonCodes": [],
    "requestType": "LEAVE",
    "rulesFailed": [],
    "rulesPassed": [
      "LV-01",
      "LV-02",
      "LV-03",
      "... 8 more"
    ],
    "approvalPath": [
      {
        "role": "DIRECT_MANAGER",
        "level": 1,
        "skipped": false,
        "escalated": false,
        "approvalRuleId": "AR-LV-001",
        "approverEmployeeId": "EMP-000043"
      }
    ],
    "businessDays": 5,
    "rulesSkipped": [],
    "rulesEvaluated": [
      {
        "ruleId": "LV-01",
        "details": {},
        "message": "Employee is active",
        "verdict": "PASS",
        "ruleName": "EMPLOYEE_ACTIVE"
      },
      {
        "ruleId": "LV-02",
        "details": {},
        "message": "Policy LPOL-ANNUAL-US is active and effective",
        "verdict": "PASS",
        "ruleName": "POLICY_EFFECTIVE"
      },
      {
        "ruleId": "LV-03",
        "details": {},
        "message": "Employee is assigned to the policy",
        "verdict": "PASS",
        "ruleName": "EMPLOYEE_ELIGIBLE"
      },
      "... 8 more"
    ],
    "applicablePolicy": "LPOL-ANNUAL-US",
    "approvalPathText": "DIRECT_MANAGER(EMP-000043)"
  },
  "approvalPath": [
    {
      "approvalStepId": "APR-LVE-2026-000893-01",
      "requestType": "LEAVE",
      "requestId": "LVE-2026-000893",
      "stepSequence": 1,
      "approvalLevel": 1,
      "approverRole": "DIRECT_MANAGER",
      "approverEmployeeId": "EMP-000043",
      "approvalStatus": "PENDING",
      "approvalRuleId": "AR-LV-001",
      "assignedAt": "2026-11-12T20:11:34.558855Z"
    }
  ]
}
```

#### The approver approves the step

```http
POST /api/v1/approvals/APR-LVE-2026-000893-01/approve
Content-Type: application/json

{
  "actorEmployeeId": "EMP-000043",
  "comments": "Enjoy",
  "reason": "approved"
}
```

Response `200 OK`

```json
{
  "approvalStepId": "APR-LVE-2026-000893-01",
  "requestType": "LEAVE",
  "requestId": "LVE-2026-000893",
  "stepSequence": 1,
  "approvalLevel": 1,
  "approverRole": "DIRECT_MANAGER",
  "approverEmployeeId": "EMP-000043",
  "approvalStatus": "APPROVED",
  "approvalRuleId": "AR-LV-001",
  "assignedAt": "2026-11-12T20:11:34.558855Z",
  "actionAt": "2026-11-12T20:11:34.727396Z",
  "comments": "Enjoy",
  "decisionReason": "approved"
}
```

#### The request after the final approval

```http
GET /api/v1/leave-requests/LVE-2026-000893
```

Response `200 OK`

```json
{
  "leaveRequestId": "LVE-2026-000893",
  "employeeId": "EMP-000086",
  "leavePolicyId": "LPOL-ANNUAL-US",
  "startDate": "2026-12-07",
  "endDate": "2026-12-11",
  "requestedDays": 5.0,
  "calculatedBusinessDays": 5.0,
  "reason": "Family holiday",
  "submissionDate": "2026-11-12",
  "requestStatus": "APPROVED",
  "decision": "APPROVED",
  "createdAt": "2026-11-12T20:11:34.337741Z",
  "updatedAt": "2026-11-12T20:11:34.760273Z"
}
```

#### The balance after approval: the days moved from `pendingBalance` to `usedBalance`

```http
GET /api/v1/employees/EMP-000086/leave-balances
```

Response `200 OK`

```json
[
  {
    "leaveBalanceId": "LBAL-000171",
    "employeeId": "EMP-000086",
    "leavePolicyId": "LPOL-ANNUAL-US",
    "year": 2026,
    "openingBalance": 15.0,
    "accruedBalance": 0.0,
    "carriedForwardBalance": 4.0,
    "usedBalance": 7.0,
    "pendingBalance": 3.0,
    "availableBalance": 9.0,
    "lastUpdatedAt": "2026-11-12T20:11:34.755090Z"
  },
  {
    "leaveBalanceId": "LBAL-000172",
    "employeeId": "EMP-000086",
    "leavePolicyId": "LPOL-SICK",
    "year": 2026,
    "openingBalance": 10.0,
    "accruedBalance": 0.0,
    "carriedForwardBalance": 0.0,
    "usedBalance": 0.0,
    "pendingBalance": 0.0,
    "availableBalance": 10.0,
    "lastUpdatedAt": "2026-02-18T17:34:00Z"
  }
]
```

#### A request that breaks the rules is accepted by the API and rejected by the evaluation

About four weeks exceed the policy's maximum number of consecutive days (LV-07) and the available balance (LV-08). The submission succeeds (`201`); the decision and its reason codes are in the body.

```http
POST /api/v1/leave-requests?evaluate=true
Content-Type: application/json

{
  "employeeId": "EMP-000086",
  "leavePolicyId": "LPOL-ANNUAL-US",
  "startDate": "2027-02-01",
  "endDate": "2027-02-26",
  "reason": "Long trip"
}
```

Response `201 Created`

```json
{
  "leaveRequestId": "LVE-2026-000894",
  "employeeId": "EMP-000086",
  "leavePolicyId": "LPOL-ANNUAL-US",
  "startDate": "2027-02-01",
  "endDate": "2027-02-26",
  "requestedDays": 26.0,
  "calculatedBusinessDays": 19.0,
  "reason": "Long trip",
  "submissionDate": "2026-11-12",
  "requestStatus": "REJECTED",
  "decision": "REJECTED",
  "decisionReason": "POLICY_LIMIT_EXCEEDED,INSUFFICIENT_BALANCE",
  "createdAt": "2026-11-12T20:11:34.802008Z",
  "updatedAt": "2026-11-12T20:11:34.826421Z"
}
```

#### A requester tries to approve their own request

Refused with `403`; the attempt is recorded in the audit trail (`APPROVAL_ATTEMPT_REJECTED`, category `VIOLATION`) and the request is unchanged.

```http
POST /api/v1/approvals/APR-LVE-2026-000895-01/approve
Content-Type: application/json

{
  "actorEmployeeId": "EMP-000086",
  "comments": "approving myself"
}
```

Response `403 Forbidden`

```json
{
  "detail": "Requester EMP-000086 cannot approve their own request",
  "instance": "/api/v1/approvals/APR-LVE-2026-000895-01/approve",
  "status": 403,
  "title": "Forbidden",
  "type": "https://nexoraflow.example/problems/forbidden",
  "timestamp": "2026-11-12T20:11:34.958481802Z",
  "reasonCode": "SELF_APPROVAL_NOT_ALLOWED"
}
```

#### The prevented attempt is in the audit trail

The event is filed under the request that was attacked (`entityType` / `entityId`); the step and the message are in `metadata`.

```http
GET /api/v1/audit-events?entityType=LEAVE_REQUEST&entityId=LVE-2026-000895&category=VIOLATION
```

Response `200 OK` (abridged)

```json
{
  "content": [
    {
      "eventId": "EVT-00017245",
      "category": "VIOLATION",
      "entityType": "LEAVE_REQUEST",
      "entityId": "LVE-2026-000895",
      "eventType": "APPROVAL_ATTEMPT_REJECTED",
      "previousState": "PENDING",
      "newState": "PENDING",
      "actor": "EMP-000086",
      "occurredAt": "2026-11-12T20:11:34.940727Z",
      "reason": "SELF_APPROVAL_NOT_ALLOWED",
      "metadata": {
        "stepId": "APR-LVE-2026-000895-01",
        "message": "Requester EMP-000086 cannot approve their own request",
        "stepStatus": "PENDING",
        "attemptedAction": "APPROVE"
      }
    }
  ]
}
```

### Expenses, approvals and reimbursement

#### Submit and evaluate an expense claim with two lines

2,500.00 USD falls into the 2,000-5,000 USD tier, which requires two approval levels (manager, then Finance Manager).

```http
POST /api/v1/expense-claims?evaluate=true
Content-Type: application/json

{
  "employeeId": "EMP-000086",
  "expensePeriodStart": "2026-11-09",
  "expensePeriodEnd": "2026-11-10",
  "costCenterId": "CC-0002",
  "currency": "USD",
  "lines": [
    {
      "category": "TRAVEL",
      "merchantName": "Skyway Air",
      "transactionDate": "2026-11-09",
      "description": "Flight to customer site",
      "amount": "1300.00",
      "receipt": {
        "fileName": "skyway-air-outbound.pdf",
        "fileType": "PDF",
        "fileSizeBytes": 183240,
        "receiptHash": "63e936135d5f992aa8486a3a5ecf5c134b1350513ca57e8051e0ae8b8fa9d8d1"
      }
    },
    {
      "category": "TRAVEL",
      "merchantName": "Skyway Air",
      "transactionDate": "2026-11-10",
      "description": "Return flight",
      "amount": "1200.00",
      "receipt": {
        "fileName": "skyway-air-return.pdf",
        "fileType": "PDF",
        "fileSizeBytes": 176912,
        "receiptHash": "a22aee2db07b90c326c131eb81f9b816a53cb0d797b7596107206195c53f1d9f"
      }
    }
  ]
}
```

Response `201 Created`

```json
{
  "expenseClaimId": "EXP-2026-001218",
  "employeeId": "EMP-000086",
  "claimDate": "2026-11-12",
  "expensePeriodStart": "2026-11-09",
  "expensePeriodEnd": "2026-11-10",
  "submissionDate": "2026-11-12",
  "costCenterId": "CC-0002",
  "submittedCostCenterRef": "CC-0002",
  "currency": "USD",
  "totalAmount": 2500.0,
  "claimStatus": "PENDING_APPROVAL",
  "eligibilityStatus": "ELIGIBLE",
  "decision": "PENDING_APPROVAL",
  "decisionReason": "",
  "createdAt": "2026-11-12T20:11:35.160424Z",
  "updatedAt": "2026-11-12T20:11:35.346212Z",
  "lines": [
    {
      "expenseLineId": "EXPL-EXP-2026-001218-01",
      "lineNumber": 1,
      "expensePolicyId": "XPOL-TRAVEL-US",
      "expenseCategory": "TRAVEL",
      "merchantName": "Skyway Air",
      "transactionDate": "2026-11-09",
      "description": "Flight to customer site",
      "amount": 1300.0,
      "currency": "USD",
      "amountReporting": 1300.0,
      "receiptId": "RCP-002350",
      "costCenterId": "CC-0002",
      "lineStatus": "VALID"
    },
    {
      "expenseLineId": "EXPL-EXP-2026-001218-02",
      "lineNumber": 2,
      "expensePolicyId": "XPOL-TRAVEL-US",
      "expenseCategory": "TRAVEL",
      "merchantName": "Skyway Air",
      "transactionDate": "2026-11-10",
      "description": "Return flight",
      "amount": 1200.0,
      "currency": "USD",
      "amountReporting": 1200.0,
      "receiptId": "RCP-002351",
      "costCenterId": "CC-0002",
      "lineStatus": "VALID"
    }
  ]
}
```

#### Explanation: rules and the approval path of the claim

```http
GET /api/v1/expense-claims/EXP-2026-001218/explanation
```

Response `200 OK` (abridged)

```json
{
  "requestId": "EXP-2026-001218",
  "currentStatus": "PENDING_APPROVAL",
  "decision": "PENDING_APPROVAL",
  "reasonCodes": [],
  "approvalPath": [
    {
      "approvalStepId": "APR-EXP-2026-001218-01",
      "requestType": "EXPENSE",
      "requestId": "EXP-2026-001218",
      "stepSequence": 1,
      "approvalLevel": 1,
      "approverRole": "MANAGER",
      "approverEmployeeId": "EMP-000013",
      "approvalStatus": "PENDING",
      "approvalRuleId": "AR-EX-003",
      "assignedAt": "2026-11-12T20:11:35.354359Z"
    },
    {
      "approvalStepId": "APR-EXP-2026-001218-02",
      "requestType": "EXPENSE",
      "requestId": "EXP-2026-001218",
      "stepSequence": 2,
      "approvalLevel": 2,
      "approverRole": "FINANCE_REVIEWER",
      "approverEmployeeId": "EMP-000024",
      "approvalStatus": "WAITING",
      "approvalRuleId": "AR-EX-004"
    }
  ]
}
```

#### The level-2 approver acts before level 1 has completed

Refused with `409 INVALID_STEP_ORDER`; steps must follow the configured order. The attempt is audited.

```http
POST /api/v1/approvals/APR-EXP-2026-001218-02/approve
Content-Type: application/json

{
  "actorEmployeeId": "EMP-000024",
  "comments": "too early"
}
```

Response `409 Conflict`

```json
{
  "detail": "An earlier approval step must be completed first",
  "instance": "/api/v1/approvals/APR-EXP-2026-001218-02/approve",
  "status": 409,
  "title": "Conflict",
  "type": "https://nexoraflow.example/problems/conflict",
  "timestamp": "2026-11-12T20:11:35.430224689Z",
  "reasonCode": "INVALID_STEP_ORDER"
}
```

#### Level 1 approves

```http
POST /api/v1/approvals/APR-EXP-2026-001218-01/approve
Content-Type: application/json

{
  "actorEmployeeId": "EMP-000013",
  "comments": "ok"
}
```

Response `200 OK`

```json
{
  "approvalStepId": "APR-EXP-2026-001218-01",
  "requestType": "EXPENSE",
  "requestId": "EXP-2026-001218",
  "stepSequence": 1,
  "approvalLevel": 1,
  "approverRole": "MANAGER",
  "approverEmployeeId": "EMP-000013",
  "approvalStatus": "APPROVED",
  "approvalRuleId": "AR-EX-003",
  "assignedAt": "2026-11-12T20:11:35.354359Z",
  "actionAt": "2026-11-12T20:11:35.456089Z",
  "comments": "ok"
}
```

#### Level 2 approves: the claim becomes APPROVED

```http
POST /api/v1/approvals/APR-EXP-2026-001218-02/approve
Content-Type: application/json

{
  "actorEmployeeId": "EMP-000024",
  "comments": "ok"
}
```

Response `200 OK`

```json
{
  "approvalStepId": "APR-EXP-2026-001218-02",
  "requestType": "EXPENSE",
  "requestId": "EXP-2026-001218",
  "stepSequence": 2,
  "approvalLevel": 2,
  "approverRole": "FINANCE_REVIEWER",
  "approverEmployeeId": "EMP-000024",
  "approvalStatus": "APPROVED",
  "approvalRuleId": "AR-EX-004",
  "assignedAt": "2026-11-12T20:11:35.456089Z",
  "actionAt": "2026-11-12T20:11:35.497784Z",
  "comments": "ok"
}
```

#### The claim after the final approval

```http
GET /api/v1/expense-claims/EXP-2026-001218
```

Response `200 OK` (abridged)

```json
{
  "expenseClaimId": "EXP-2026-001218",
  "employeeId": "EMP-000086",
  "currency": "USD",
  "totalAmount": 2500.0,
  "claimStatus": "APPROVED",
  "eligibilityStatus": "ELIGIBLE",
  "decision": "APPROVED"
}
```

#### The reimbursement was created automatically at final approval

Nobody has to request it: the final approval creates exactly one reimbursement (`PENDING_PAYMENT`) for the approved, eligible claim.

```http
GET /api/v1/reimbursements?employeeId=EMP-000086
```

Response `200 OK`

```json
{
  "content": [
    {
      "reimbursementId": "RMB-2026-000120",
      "expenseClaimId": "EXP-2026-000137",
      "employeeId": "EMP-000086",
      "approvedAmount": 12.0,
      "currency": "USD",
      "reimbursementStatus": "PAID",
      "approvedAt": "2026-03-04T21:05:00Z",
      "processedAt": "2026-03-09T10:00:00Z",
      "paymentReference": "PAY-2026-000093",
      "createdAt": "2026-03-04T21:05:00Z"
    },
    {
      "reimbursementId": "RMB-2026-000878",
      "expenseClaimId": "EXP-2026-001218",
      "employeeId": "EMP-000086",
      "approvedAmount": 2500.0,
      "currency": "USD",
      "reimbursementStatus": "PENDING_PAYMENT",
      "approvedAt": "2026-11-12T20:11:35.537833Z",
      "createdAt": "2026-11-12T20:11:35.541492Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 2,
  "totalPages": 1
}
```

#### Requesting it again is refused and recorded

Idempotent by design: exactly one reimbursement exists per claim; the duplicate attempt is stored in `reimbursement_attempt` and audited (`REIMBURSEMENT_DUPLICATE_REJECTED`).

```http
POST /api/v1/reimbursements
Content-Type: application/json

{
  "expenseClaimId": "EXP-2026-001218",
  "requestedBy": "EMP-000024"
}
```

Response `409 Conflict`

```json
{
  "detail": "Claim EXP-2026-001218 already has reimbursement RMB-2026-000878",
  "instance": "/api/v1/reimbursements",
  "status": 409,
  "title": "Conflict",
  "type": "https://nexoraflow.example/problems/conflict",
  "timestamp": "2026-11-12T20:11:35.613598975Z",
  "reasonCode": "DUPLICATE_REIMBURSEMENT"
}
```

#### Mark the reimbursement as paid (simulated payroll run)

```http
POST /api/v1/reimbursements/RMB-2026-000878/process
Content-Type: application/json

{
  "actor": "EMP-000024"
}
```

Response `200 OK`

```json
{
  "reimbursementId": "RMB-2026-000878",
  "expenseClaimId": "EXP-2026-001218",
  "employeeId": "EMP-000086",
  "approvedAmount": 2500.0,
  "currency": "USD",
  "reimbursementStatus": "PAID",
  "approvedAt": "2026-11-12T20:11:35.537833Z",
  "processedAt": "2026-11-12T20:11:35.633628Z",
  "paymentReference": "PAY-2026-000586",
  "createdAt": "2026-11-12T20:11:35.541492Z"
}
```

#### Budget position of a cost center (derived from source claims)

```http
GET /api/v1/cost-centers/CC-0002/budget
```

Response `200 OK`

```json
{
  "budgetId": "BUD-CC-0002-2026-11",
  "costCenterId": "CC-0002",
  "periodStart": "2026-11-01",
  "periodEnd": "2026-11-30",
  "currency": "USD",
  "allocated": 12500.0,
  "approvedSpend": 2500.0,
  "pendingCommitted": 0.0,
  "remaining": 10000.0
}
```

### Errors

#### Validation error (400)

```http
POST /api/v1/leave-requests
Content-Type: application/json

{
  "employeeId": "EMP-000086",
  "leavePolicyId": "LPOL-ANNUAL-US"
}
```

Response `400 Bad Request`

```json
{
  "detail": "Request validation failed",
  "instance": "/api/v1/leave-requests",
  "status": 400,
  "title": "Bad Request",
  "type": "https://nexoraflow.example/problems/bad-request",
  "timestamp": "2026-11-12T20:11:35.674045614Z",
  "errors": [
    {
      "field": "startDate",
      "message": "must not be null"
    },
    {
      "field": "endDate",
      "message": "must not be null"
    }
  ]
}
```

#### Intake rejection: unknown employee (422), preserved in staging

The body carries the `submissionId` under which the submission was preserved; nothing is silently discarded.

```http
POST /api/v1/expense-claims
Content-Type: application/json

{
  "employeeId": "EMP-999999",
  "expensePeriodStart": "2026-11-09",
  "expensePeriodEnd": "2026-11-09",
  "costCenterId": "CC-0002",
  "currency": "USD",
  "lines": [
    {
      "category": "MEALS",
      "merchantName": "Cafe Roma",
      "transactionDate": "2026-11-09",
      "description": "Lunch",
      "amount": "20.00"
    }
  ]
}
```

Response `422 Unprocessable Entity`

```json
{
  "detail": "The submission failed intake validation and was preserved for review",
  "instance": "/api/v1/expense-claims",
  "status": 422,
  "title": "Unprocessable Entity",
  "type": "https://nexoraflow.example/problems/unprocessable-entity",
  "timestamp": "2026-11-12T20:11:35.693257473Z",
  "submissionId": "SUB-002124",
  "errors": [
    "EMPLOYEE_NOT_FOUND: EMP-999999"
  ]
}
```

#### Not found (404)

```http
GET /api/v1/leave-requests/LVE-2026-999999
```

Response `404 Not Found`

```json
{
  "detail": "Leave request 'LVE-2026-999999' was not found",
  "instance": "/api/v1/leave-requests/LVE-2026-999999",
  "status": 404,
  "title": "Not Found",
  "type": "https://nexoraflow.example/problems/not-found",
  "timestamp": "2026-11-12T20:11:35.701043429Z"
}
```

### Reports, validation and evaluation

#### Operational report (excerpt)

```http
GET /api/v1/reports/expenses
```

Response `200 OK` (abridged)

```json
{
  "totals": {
    "total_claims": 1218,
    "claimed_usd": 660587.35,
    "approved_usd": 527875.49,
    "rejected_usd": 55910.2,
    "awaiting_approval": 134,
    "policy_violations": 39,
    "missing_receipts": 15,
    "duplicate_receipts": 8,
    "suspicious_split_claims": 18
  },
  "distributionByCategory": [
    {
      "category": "TRAVEL",
      "lines": 457,
      "amount_usd": 281574.96
    },
    {
      "category": "TRAINING",
      "lines": 142,
      "amount_usd": 193918.21
    },
    {
      "category": "OFFICE_SUPPLIES",
      "lines": 375,
      "amount_usd": 60508.61
    },
    "... 3 more"
  ]
}
```

#### Latest validation report (excerpt)

```http
GET /api/v1/validation/report
```

Response `404 Not Found` (abridged)

```json
{}
```

#### Latest evaluation report (excerpt)

```http
GET /api/v1/evaluation/report
```

Response `404 Not Found` (abridged)

```json
{}
```
