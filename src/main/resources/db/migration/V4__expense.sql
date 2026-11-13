-- =====================================================================================
-- V4: receipts, expense claims and lines, intake staging, detected anomalies
-- Deliberately invalid submissions are PRESERVED:
--   * raw payloads live in submission_record (staging) with explicit validation errors;
--   * canonical rows never carry an invalid foreign key - the original value is kept in
--     submitted_*_ref columns and the FK column is left NULL.
-- =====================================================================================

CREATE TABLE receipt (
    receipt_id       VARCHAR(40)  PRIMARY KEY,
    employee_id      VARCHAR(40)  NOT NULL REFERENCES employee (employee_id),
    file_name        VARCHAR(200) NOT NULL,
    file_type        VARCHAR(20)  NOT NULL,
    file_size_bytes  BIGINT       NOT NULL CHECK (file_size_bytes > 0),
    uploaded_at      TIMESTAMPTZ  NOT NULL,
    receipt_hash     VARCHAR(64)  NOT NULL CHECK (receipt_hash ~ '^[0-9a-f]{64}$'),
    receipt_status   VARCHAR(20)  NOT NULL CHECK (receipt_status IN ('VALID', 'INVALID', 'QUARANTINED')),
    source_reference VARCHAR(120)
);
-- deliberately NOT unique: duplicate hashes are valid evidence that the duplicate-receipt rule must detect
CREATE INDEX ix_receipt_hash ON receipt (receipt_hash);
CREATE INDEX ix_receipt_employee ON receipt (employee_id);

CREATE TABLE expense_claim (
    expense_claim_id          VARCHAR(40)   PRIMARY KEY,
    employee_id               VARCHAR(40)   NOT NULL REFERENCES employee (employee_id),
    claim_date                DATE          NOT NULL,
    expense_period_start      DATE          NOT NULL,
    expense_period_end        DATE          NOT NULL,
    submission_date           DATE          NOT NULL,
    submitted_at              TIMESTAMPTZ   NOT NULL,
    cost_center_id            VARCHAR(40)   REFERENCES cost_center (cost_center_id),
    submitted_cost_center_ref VARCHAR(80),
    currency                  VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_amount              NUMERIC(18,2) NOT NULL CHECK (total_amount >= 0),
    claim_status              VARCHAR(20)   NOT NULL CHECK (claim_status IN
                                    ('SUBMITTED', 'PENDING_APPROVAL', 'NEEDS_REVIEW', 'FLAGGED', 'ON_HOLD',
                                     'APPROVED', 'REJECTED', 'CANCELLED')),
    eligibility_status        VARCHAR(20)   NOT NULL CHECK (eligibility_status IN
                                    ('NOT_EVALUATED', 'ELIGIBLE', 'INELIGIBLE', 'REVIEW_REQUIRED')),
    decision                  VARCHAR(20)   CHECK (decision IN
                                    ('APPROVED', 'REJECTED', 'PENDING_APPROVAL', 'NEEDS_REVIEW', 'FLAGGED', 'ON_HOLD', 'CANCELLED')),
    decision_reason           VARCHAR(500),
    created_at                TIMESTAMPTZ   NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL,
    version                   BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_claim_period CHECK (expense_period_start <= expense_period_end OR claim_status = 'REJECTED'),
    -- a claim that is live in the approval workflow must have a valid cost center
    CONSTRAINT ck_claim_cost_center_when_live CHECK (cost_center_id IS NOT NULL OR claim_status IN
                                    ('SUBMITTED', 'ON_HOLD', 'REJECTED', 'FLAGGED', 'NEEDS_REVIEW', 'CANCELLED'))
);
CREATE INDEX ix_claim_employee ON expense_claim (employee_id, claim_date);
CREATE INDEX ix_claim_status ON expense_claim (claim_status);
CREATE INDEX ix_claim_cost_center ON expense_claim (cost_center_id, claim_date);
CREATE INDEX ix_claim_submission ON expense_claim (submission_date);

CREATE TABLE expense_line (
    expense_line_id       VARCHAR(40)   PRIMARY KEY,
    expense_claim_id      VARCHAR(40)   NOT NULL REFERENCES expense_claim (expense_claim_id),
    line_number           INTEGER       NOT NULL CHECK (line_number BETWEEN 1 AND 99),
    expense_policy_id     VARCHAR(40)   REFERENCES expense_policy (expense_policy_id),
    expense_category      VARCHAR(30)   NOT NULL CHECK (expense_category IN
                                ('TRAVEL', 'MEALS', 'LODGING', 'OFFICE_SUPPLIES', 'TRAINING', 'CLIENT_ENTERTAINMENT')),
    merchant_name         VARCHAR(160)  NOT NULL,
    transaction_date      DATE          NOT NULL,
    description           VARCHAR(300),
    amount                NUMERIC(18,2) NOT NULL,
    currency              VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount_reporting      NUMERIC(18,2),
    receipt_id            VARCHAR(40)   REFERENCES receipt (receipt_id),
    submitted_receipt_ref VARCHAR(80),
    cost_center_id        VARCHAR(40)   REFERENCES cost_center (cost_center_id),
    line_status           VARCHAR(20)   NOT NULL CHECK (line_status IN ('SUBMITTED', 'VALID', 'INVALID', 'FLAGGED')),
    validation_reason     VARCHAR(300),
    CONSTRAINT ux_expense_line_number UNIQUE (expense_claim_id, line_number),
    -- a non-positive amount may only exist as an explicitly INVALID (rejected) line
    CONSTRAINT ck_line_amount CHECK (amount > 0 OR line_status = 'INVALID')
);
CREATE INDEX ix_line_claim ON expense_line (expense_claim_id);
CREATE INDEX ix_line_receipt ON expense_line (receipt_id);
CREATE INDEX ix_line_dedup ON expense_line (merchant_name, transaction_date, amount);
CREATE INDEX ix_line_category_date ON expense_line (expense_category, transaction_date);

-- Intake staging: the original submission exactly as received, plus the intake verdict.
CREATE TABLE submission_record (
    submission_id     VARCHAR(40)  PRIMARY KEY,
    request_type      VARCHAR(10)  NOT NULL CHECK (request_type IN ('LEAVE', 'EXPENSE')),
    received_at       TIMESTAMPTZ  NOT NULL,
    submitted_by_ref  VARCHAR(80),
    raw_payload       JSONB        NOT NULL,
    intake_status     VARCHAR(30)  NOT NULL CHECK (intake_status IN
                                ('ACCEPTED', 'ACCEPTED_WITH_ERRORS', 'REJECTED_AT_INTAKE')),
    validation_errors JSONB,
    leave_request_id  VARCHAR(40)  REFERENCES leave_request (leave_request_id),
    expense_claim_id  VARCHAR(40)  REFERENCES expense_claim (expense_claim_id),
    CONSTRAINT ck_submission_single_target CHECK (leave_request_id IS NULL OR expense_claim_id IS NULL),
    CONSTRAINT ck_submission_canonical CHECK (intake_status = 'REJECTED_AT_INTAKE'
                                              OR num_nonnulls(leave_request_id, expense_claim_id) = 1)
);
CREATE INDEX ix_submission_status ON submission_record (intake_status);
CREATE INDEX ix_submission_leave ON submission_record (leave_request_id);
CREATE INDEX ix_submission_claim ON submission_record (expense_claim_id);

-- Anomalies detected by the engines (domain-agnostic generalisation of "ExpenseAnomaly").
CREATE TABLE detected_anomaly (
    anomaly_id         VARCHAR(40)  PRIMARY KEY,
    anomaly_domain     VARCHAR(20)  NOT NULL CHECK (anomaly_domain IN ('LEAVE', 'EXPENSE', 'APPROVAL', 'REIMBURSEMENT')),
    anomaly_type       VARCHAR(40)  NOT NULL,
    entity_type        VARCHAR(30)  NOT NULL,
    entity_id          VARCHAR(40)  NOT NULL,
    related_entity_ids VARCHAR(500),
    severity           VARCHAR(10)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    reason_code        VARCHAR(40)  NOT NULL,
    detected_at        TIMESTAMPTZ  NOT NULL,
    details            JSONB,
    status             VARCHAR(12)  NOT NULL CHECK (status IN ('OPEN', 'CONFIRMED', 'DISMISSED'))
);
CREATE INDEX ix_anomaly_entity ON detected_anomaly (entity_type, entity_id);
CREATE INDEX ix_anomaly_type ON detected_anomaly (anomaly_type);
