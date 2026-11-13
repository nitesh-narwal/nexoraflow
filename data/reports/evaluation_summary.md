# Evaluation summary

> Seed `20261022`, profile `MEDIUM`. Metrics compare engine results with ground_truth.label, which the decision engines never read. 'Detected' = the expected outcome AND the expected reason / evidence were both found. False positives = valid records on which the engine raised an exception (violation code or FLAGGED/ON_HOLD/NEEDS_REVIEW) that was not expected.

### Anomaly detection (anomaly-labelled entities vs. valid entities)

| Anomalies labelled | Detected | Undetected (FN) | False positives | Precision | Recall | F1 |
|---:|---:|---:|---:|---:|---:|---:|
| 247 | 247 | 0 | 0 | 1.0 | 1.0 | 1.0 |

### Ordinary workflow decisions

| Evaluated | Correct outcome | Incorrect | Outcome accuracy | Paths evaluated | Correct paths | Path accuracy | Decision accuracy |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 2119 | 2119 | 0 | 1.0 | 1922 | 1922 | 1.0 | 1.0 |

### Reimbursement decisions

| Claims evaluated | Correct | Incorrect | Accuracy | Claims with >1 reimbursement |
|---:|---:|---:|---:|---:|
| 1217 | 1217 | 0 | 1.0 | 0 |

### Safeguards that fired (from the audit trail)

- Invalid reimbursement requests prevented (duplicate or not eligible): **16** (duplicates: 12)
- Unauthorized / self-approval attempts prevented: **14**
- Failed lifecycle transitions rejected and recorded: **8**
- Submissions preserved with invalid references: **34** (rejected at intake: 10)
- Foreign-key validation failures (check DI-02): **0**
- Duplicate business identifiers (check DI-01): **0**

### Record volumes

Total canonical records: **39491**; valid labelled entities: 1872; entities of designed anomaly scenarios: 247; unlabelled requests: 0

| Table | Rows |
|---|---:|
| employee | 400 |
| department | 8 |
| cost_center | 45 |
| employee_cost_center | 495 |
| leave_policy | 6 |
| expense_policy | 31 |
| approval_rule | 12 |
| budget | 504 |
| employee_policy_assignment | 746 |
| leave_balance | 746 |
| leave_balance_transaction | 3880 |
| leave_request | 892 |
| expense_claim | 1217 |
| expense_line | 2599 |
| receipt | 2349 |
| approval_step | 2169 |
| reimbursement | 877 |
| reimbursement_attempt | 893 |
| audit_event | 17226 |
| decision_trace | 2109 |
| detected_anomaly | 52 |
| submission_record | 2119 |
| holiday | 116 |
| ground_truth_labels | 2119 |

### Per scenario

| Issue type | Anomaly | Entities | Outcome correct | Evidence found |
|---|---|---:|---:|---:|
| CANCELLED_LEAVE | no | 6 | 6 | 6 |
| CLOSED_COST_CENTER | yes | 2 | 2 | 2 |
| COMPLETED_APPROVAL_OVERWRITE_ATTEMPT | yes | 4 | 4 | 4 |
| DAILY_LIMIT_EXCEEDED | yes | 4 | 4 | 4 |
| DOCUMENTATION_MISSING | yes | 5 | 5 | 5 |
| DUPLICATE_EXPENSE_SUBMISSION | yes | 8 | 8 | 8 |
| DUPLICATE_RECEIPT | yes | 8 | 8 | 8 |
| DUPLICATE_REIMBURSEMENT_ATTEMPT | yes | 8 | 8 | 8 |
| EMPLOYEE_NOT_ELIGIBLE | yes | 4 | 4 | 4 |
| HOLIDAY_OR_NON_WORKING_DAY_ONLY | yes | 5 | 5 | 5 |
| INACTIVE_APPROVAL_POLICY | yes | 6 | 6 | 6 |
| INACTIVE_EMPLOYEE | yes | 10 | 10 | 10 |
| INSUFFICIENT_NOTICE | yes | 4 | 4 | 4 |
| INVALID_APPROVAL_HIERARCHY | yes | 2 | 2 | 2 |
| INVALID_CURRENCY | yes | 6 | 6 | 6 |
| INVALID_DATE_RANGE | yes | 12 | 12 | 12 |
| INVALID_EMPLOYEE_REFERENCE | yes | 8 | 8 | 8 |
| INVALID_RECEIPT_REFERENCE | yes | 4 | 4 | 4 |
| MANAGER_UNAVAILABLE | yes | 8 | 8 | 8 |
| MAX_CONSECUTIVE_DAYS_EXCEEDED | yes | 5 | 5 | 5 |
| MISSING_COST_CENTER | yes | 8 | 8 | 8 |
| MISSING_RECEIPT | yes | 15 | 15 | 15 |
| MONTHLY_BUDGET_EXHAUSTED | yes | 6 | 6 | 6 |
| MONTHLY_LIMIT_EXCEEDED | yes | 3 | 3 | 3 |
| MULTI_LEVEL_APPROVAL | no | 6 | 6 | 6 |
| NEGATIVE_BALANCE | yes | 12 | 12 | 12 |
| NONE | no | 1844 | 1844 | 1844 |
| OUTSIDE_EMPLOYMENT_PERIOD | yes | 12 | 12 | 12 |
| OUT_OF_ORDER_APPROVAL_ATTEMPT | yes | 4 | 4 | 4 |
| OVERLAPPING_APPROVED_LEAVE | yes | 7 | 7 | 7 |
| POLICY_EFFECTIVE_DATE_MISMATCH | yes | 9 | 9 | 9 |
| REIMBURSEMENT_BEFORE_APPROVAL | yes | 4 | 4 | 4 |
| SELF_APPROVAL_ATTEMPT | yes | 10 | 10 | 10 |
| SPLIT_CLAIM_CONTROL | no | 16 | 16 | 16 |
| SUSPICIOUS_SPLIT_CLAIM | yes | 18 | 18 | 18 |
| TRANSACTION_LIMIT_EXCEEDED | yes | 8 | 8 | 8 |
| UNAUTHORIZED_APPROVAL_ATTEMPT | yes | 4 | 4 | 4 |
| UNAUTHORIZED_COST_CENTER_USAGE | yes | 8 | 8 | 8 |
| UNSUPPORTED_CATEGORY | yes | 2 | 2 | 2 |
| UNSUPPORTED_RECEIPT_TYPE | yes | 4 | 4 | 4 |

No mismatches between engine results and ground truth.
