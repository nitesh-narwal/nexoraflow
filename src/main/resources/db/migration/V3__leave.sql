-- =====================================================================================
-- V3: leave balances, leave requests and the balance ledger
-- available_balance is a GENERATED column: it can never be randomised or drift from its components.
-- =====================================================================================

CREATE TABLE leave_balance (
    leave_balance_id        VARCHAR(40)  PRIMARY KEY,
    employee_id             VARCHAR(40)  NOT NULL REFERENCES employee (employee_id),
    leave_policy_id         VARCHAR(40)  NOT NULL REFERENCES leave_policy (leave_policy_id),
    balance_year            INTEGER      NOT NULL CHECK (balance_year BETWEEN 2000 AND 2100),
    opening_balance         NUMERIC(7,2) NOT NULL DEFAULT 0 CHECK (opening_balance >= 0),
    accrued_balance         NUMERIC(7,2) NOT NULL DEFAULT 0 CHECK (accrued_balance >= 0),
    carried_forward_balance NUMERIC(7,2) NOT NULL DEFAULT 0 CHECK (carried_forward_balance >= 0),
    used_balance            NUMERIC(7,2) NOT NULL DEFAULT 0 CHECK (used_balance >= 0),
    pending_balance         NUMERIC(7,2) NOT NULL DEFAULT 0 CHECK (pending_balance >= 0),
    available_balance       NUMERIC(7,2) GENERATED ALWAYS AS
        (opening_balance + accrued_balance + carried_forward_balance - used_balance - pending_balance) STORED,
    last_updated_at         TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ux_leave_balance UNIQUE (employee_id, leave_policy_id, balance_year)
);
CREATE INDEX ix_leave_balance_employee ON leave_balance (employee_id);

CREATE TABLE leave_request (
    leave_request_id         VARCHAR(40)  PRIMARY KEY,
    employee_id              VARCHAR(40)  NOT NULL REFERENCES employee (employee_id),
    leave_policy_id          VARCHAR(40)  NOT NULL REFERENCES leave_policy (leave_policy_id),
    start_date               DATE         NOT NULL,
    end_date                 DATE         NOT NULL,
    requested_days           NUMERIC(6,2) NOT NULL CHECK (requested_days >= 0),
    reason                   VARCHAR(500),
    submission_date          DATE         NOT NULL,
    submitted_at             TIMESTAMPTZ  NOT NULL,
    request_status           VARCHAR(20)  NOT NULL CHECK (request_status IN
                                 ('SUBMITTED', 'PENDING_APPROVAL', 'NEEDS_REVIEW', 'FLAGGED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    calculated_business_days NUMERIC(6,2) CHECK (calculated_business_days >= 0),
    decision                 VARCHAR(20)  CHECK (decision IN
                                 ('APPROVED', 'REJECTED', 'PENDING_APPROVAL', 'NEEDS_REVIEW', 'FLAGGED', 'ON_HOLD', 'CANCELLED')),
    decision_reason          VARCHAR(500),
    supporting_document_ref  VARCHAR(200),
    cancelled_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    -- an inverted range is only ever allowed on a REJECTED record (preserved submission, never a live request)
    CONSTRAINT ck_leave_date_range CHECK (start_date <= end_date OR request_status = 'REJECTED')
);
CREATE INDEX ix_leave_request_employee_dates ON leave_request (employee_id, start_date, end_date);
CREATE INDEX ix_leave_request_status ON leave_request (request_status);
CREATE INDEX ix_leave_request_policy ON leave_request (leave_policy_id);
CREATE INDEX ix_leave_request_submission ON leave_request (submission_date);

-- Every movement of a balance component is an explicit, immutable ledger row.
CREATE TABLE leave_balance_transaction (
    txn_id                 VARCHAR(40)  PRIMARY KEY,
    leave_balance_id       VARCHAR(40)  NOT NULL REFERENCES leave_balance (leave_balance_id),
    leave_request_id       VARCHAR(40)  REFERENCES leave_request (leave_request_id),
    txn_type               VARCHAR(20)  NOT NULL CHECK (txn_type IN
                                 ('OPENING_GRANT', 'ACCRUAL', 'CARRY_FORWARD', 'PENDING_RESERVE', 'PENDING_RELEASE',
                                  'CONSUME', 'RESTORE', 'MANUAL_ADJUSTMENT')),
    period_key             VARCHAR(20),
    opening_delta          NUMERIC(7,2) NOT NULL DEFAULT 0,
    accrued_delta          NUMERIC(7,2) NOT NULL DEFAULT 0,
    carried_forward_delta  NUMERIC(7,2) NOT NULL DEFAULT 0,
    used_delta             NUMERIC(7,2) NOT NULL DEFAULT 0,
    pending_delta          NUMERIC(7,2) NOT NULL DEFAULT 0,
    txn_date               DATE         NOT NULL,
    occurred_at            TIMESTAMPTZ  NOT NULL,
    actor                  VARCHAR(40)  NOT NULL,
    reason                 VARCHAR(300) NOT NULL
);
CREATE INDEX ix_lbt_balance ON leave_balance_transaction (leave_balance_id, occurred_at);
CREATE INDEX ix_lbt_request ON leave_balance_transaction (leave_request_id);
-- accrual for a given period is posted at most once (idempotent accrual runs)
CREATE UNIQUE INDEX ux_lbt_accrual_period ON leave_balance_transaction (leave_balance_id, period_key)
    WHERE txn_type = 'ACCRUAL';
-- an approved request consumes balance exactly once, and a consumption is restored at most once.
-- (PENDING_RESERVE / PENDING_RELEASE may legitimately repeat when an analyst releases a flagged request;
--  their alternation is enforced and validated in the application and by the validation module.)
CREATE UNIQUE INDEX ux_lbt_request_once ON leave_balance_transaction (leave_request_id, txn_type)
    WHERE txn_type IN ('CONSUME', 'RESTORE');

CREATE FUNCTION leave_balance_txn_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'leave_balance_transaction is append-only (% is not permitted)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_leave_balance_txn_immutable
    BEFORE UPDATE OR DELETE ON leave_balance_transaction
    FOR EACH ROW EXECUTE FUNCTION leave_balance_txn_immutable();
