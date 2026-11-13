-- =====================================================================================
-- V5: approval steps, reimbursements, audit trail and decision traces
-- History is append-only: completed approval steps, audit events and decision traces cannot be
-- updated or deleted - not even by application bugs.
-- =====================================================================================

CREATE TABLE approval_step (
    approval_step_id       VARCHAR(40)  PRIMARY KEY,
    request_type           VARCHAR(10)  NOT NULL CHECK (request_type IN ('LEAVE', 'EXPENSE')),
    leave_request_id       VARCHAR(40)  REFERENCES leave_request (leave_request_id),
    expense_claim_id       VARCHAR(40)  REFERENCES expense_claim (expense_claim_id),
    -- request_id mirrors the single populated FK so that exports expose the (request_type, request_id) pair
    request_id             VARCHAR(40)  GENERATED ALWAYS AS (COALESCE(leave_request_id, expense_claim_id)) STORED,
    step_sequence          INTEGER      NOT NULL CHECK (step_sequence >= 1),
    approval_level         INTEGER      NOT NULL CHECK (approval_level BETWEEN 1 AND 5),
    approver_role          VARCHAR(30)  NOT NULL CHECK (approver_role IN
                                 ('DIRECT_MANAGER', 'MANAGER', 'DEPARTMENT_HEAD', 'FINANCE_REVIEWER', 'FINANCE_HEAD')),
    approver_employee_id   VARCHAR(40)  NOT NULL REFERENCES employee (employee_id),
    approval_status        VARCHAR(12)  NOT NULL CHECK (approval_status IN
                                 ('WAITING', 'PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'ESCALATED', 'SKIPPED')),
    approval_rule_id       VARCHAR(40)  REFERENCES approval_rule (approval_rule_id),
    escalated_from_step_id VARCHAR(40)  REFERENCES approval_step (approval_step_id),
    assigned_at            TIMESTAMPTZ,
    action_at              TIMESTAMPTZ,
    comments               VARCHAR(500),
    decision_reason        VARCHAR(300),
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_step_single_target CHECK (
        (request_type = 'LEAVE'   AND leave_request_id IS NOT NULL AND expense_claim_id IS NULL) OR
        (request_type = 'EXPENSE' AND expense_claim_id IS NOT NULL AND leave_request_id IS NULL)),
    CONSTRAINT ck_step_action_after_assignment CHECK (action_at IS NULL OR assigned_at IS NULL OR action_at >= assigned_at),
    CONSTRAINT ck_step_actioned CHECK (approval_status NOT IN ('APPROVED', 'REJECTED') OR action_at IS NOT NULL)
);
CREATE UNIQUE INDEX ux_step_leave_sequence ON approval_step (leave_request_id, step_sequence) WHERE leave_request_id IS NOT NULL;
CREATE UNIQUE INDEX ux_step_expense_sequence ON approval_step (expense_claim_id, step_sequence) WHERE expense_claim_id IS NOT NULL;
CREATE INDEX ix_step_approver_status ON approval_step (approver_employee_id, approval_status);
CREATE INDEX ix_step_leave ON approval_step (leave_request_id);
CREATE INDEX ix_step_expense ON approval_step (expense_claim_id);
CREATE INDEX ix_step_status ON approval_step (approval_status);

CREATE FUNCTION approval_step_guard() RETURNS trigger AS $$
DECLARE
    requester VARCHAR;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Approval history is append-only: step % cannot be deleted', OLD.approval_step_id;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.leave_request_id IS NOT NULL THEN
            SELECT employee_id INTO requester FROM leave_request WHERE leave_request_id = NEW.leave_request_id;
        ELSE
            SELECT employee_id INTO requester FROM expense_claim WHERE expense_claim_id = NEW.expense_claim_id;
        END IF;
        IF requester = NEW.approver_employee_id THEN
            RAISE EXCEPTION 'Self approval is not allowed: approver % is the requester', NEW.approver_employee_id;
        END IF;
        RETURN NEW;
    END IF;

    -- UPDATE: completed steps are immutable; assignment and subject can never change
    IF OLD.approval_status IN ('APPROVED', 'REJECTED', 'CANCELLED', 'ESCALATED', 'SKIPPED') THEN
        RAISE EXCEPTION 'Approval step % is already % and cannot be modified', OLD.approval_step_id, OLD.approval_status;
    END IF;
    IF NEW.approver_employee_id <> OLD.approver_employee_id
       OR NEW.leave_request_id IS DISTINCT FROM OLD.leave_request_id
       OR NEW.expense_claim_id IS DISTINCT FROM OLD.expense_claim_id
       OR NEW.step_sequence <> OLD.step_sequence THEN
        RAISE EXCEPTION 'Approval step % assignment is immutable', OLD.approval_step_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_approval_step_guard
    BEFORE INSERT OR UPDATE OR DELETE ON approval_step
    FOR EACH ROW EXECUTE FUNCTION approval_step_guard();

CREATE TABLE reimbursement (
    reimbursement_id     VARCHAR(40)   PRIMARY KEY,
    expense_claim_id     VARCHAR(40)   NOT NULL REFERENCES expense_claim (expense_claim_id),
    employee_id          VARCHAR(40)   NOT NULL REFERENCES employee (employee_id),
    approved_amount      NUMERIC(18,2) NOT NULL CHECK (approved_amount > 0),
    currency             VARCHAR(3)    NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    reimbursement_status VARCHAR(20)   NOT NULL CHECK (reimbursement_status IN
                                 ('PENDING_PAYMENT', 'PAID', 'FAILED', 'CANCELLED')),
    approved_at          TIMESTAMPTZ   NOT NULL,
    processed_at         TIMESTAMPTZ,
    payment_reference    VARCHAR(60),
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL DEFAULT 0,
    -- at most ONE reimbursement per claim, enforced by the database itself
    CONSTRAINT ux_reimbursement_claim UNIQUE (expense_claim_id),
    CONSTRAINT ux_reimbursement_payment_ref UNIQUE (payment_reference),
    CONSTRAINT ck_reimbursement_paid CHECK (reimbursement_status <> 'PAID'
                                            OR (processed_at IS NOT NULL AND payment_reference IS NOT NULL))
);
CREATE INDEX ix_reimbursement_employee ON reimbursement (employee_id);
CREATE INDEX ix_reimbursement_status ON reimbursement (reimbursement_status);

-- Only an eligible, finally approved claim may ever receive a reimbursement.
CREATE FUNCTION reimbursement_guard() RETURNS trigger AS $$
DECLARE
    c_status   VARCHAR;
    c_elig     VARCHAR;
    c_employee VARCHAR;
    c_currency VARCHAR;
    open_steps INTEGER;
    done_steps INTEGER;
BEGIN
    SELECT claim_status, eligibility_status, employee_id, currency
      INTO c_status, c_elig, c_employee, c_currency
      FROM expense_claim WHERE expense_claim_id = NEW.expense_claim_id;

    IF c_status IS DISTINCT FROM 'APPROVED' OR c_elig IS DISTINCT FROM 'ELIGIBLE' THEN
        RAISE EXCEPTION 'Reimbursement refused: claim % is not finally approved and eligible (status=%, eligibility=%)',
            NEW.expense_claim_id, c_status, c_elig;
    END IF;

    SELECT count(*) FILTER (WHERE approval_status IN ('WAITING', 'PENDING', 'REJECTED')),
           count(*) FILTER (WHERE approval_status = 'APPROVED')
      INTO open_steps, done_steps
      FROM approval_step WHERE expense_claim_id = NEW.expense_claim_id;

    IF open_steps > 0 OR done_steps = 0 THEN
        RAISE EXCEPTION 'Reimbursement refused: claim % has incomplete approval steps', NEW.expense_claim_id;
    END IF;
    IF c_employee <> NEW.employee_id OR c_currency <> NEW.currency THEN
        RAISE EXCEPTION 'Reimbursement refused: employee/currency do not match claim %', NEW.expense_claim_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reimbursement_guard
    BEFORE INSERT ON reimbursement
    FOR EACH ROW EXECUTE FUNCTION reimbursement_guard();

-- Every attempt to create a reimbursement is recorded, including the ones the system rejects.
CREATE TABLE reimbursement_attempt (
    attempt_id       VARCHAR(40)  PRIMARY KEY,
    expense_claim_id VARCHAR(40)  NOT NULL REFERENCES expense_claim (expense_claim_id),
    attempted_at     TIMESTAMPTZ  NOT NULL,
    outcome          VARCHAR(24)  NOT NULL CHECK (outcome IN ('CREATED', 'DUPLICATE_REJECTED', 'NOT_ELIGIBLE_REJECTED')),
    reason_code      VARCHAR(40),
    reimbursement_id VARCHAR(40)  REFERENCES reimbursement (reimbursement_id),
    requested_by     VARCHAR(40)  NOT NULL,
    details          VARCHAR(300)
);
CREATE INDEX ix_rmb_attempt_claim ON reimbursement_attempt (expense_claim_id);
CREATE INDEX ix_rmb_attempt_outcome ON reimbursement_attempt (outcome);

CREATE TABLE audit_event (
    event_id       VARCHAR(40)  PRIMARY KEY,
    event_category VARCHAR(12)  NOT NULL CHECK (event_category IN
                               ('WORKFLOW', 'DECISION', 'VIOLATION', 'BALANCE', 'FINANCIAL', 'SYSTEM')),
    entity_type    VARCHAR(30)  NOT NULL,
    entity_id      VARCHAR(60)  NOT NULL,
    event_type     VARCHAR(60)  NOT NULL,
    previous_state VARCHAR(30),
    new_state      VARCHAR(30),
    actor          VARCHAR(40)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    reason         VARCHAR(500),
    metadata       JSONB
);
CREATE INDEX ix_audit_entity ON audit_event (entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_type ON audit_event (event_type);
CREATE INDEX ix_audit_category ON audit_event (event_category);
CREATE INDEX ix_audit_occurred ON audit_event (occurred_at);

-- Explainability store: one row per evaluation, with full rule-by-rule outcome.
CREATE TABLE decision_trace (
    trace_id          VARCHAR(40)  PRIMARY KEY,
    request_type      VARCHAR(10)  NOT NULL CHECK (request_type IN ('LEAVE', 'EXPENSE')),
    request_id        VARCHAR(40)  NOT NULL,
    evaluation_number INTEGER      NOT NULL CHECK (evaluation_number >= 1),
    evaluated_at      TIMESTAMPTZ  NOT NULL,
    decision          VARCHAR(20)  NOT NULL,
    reason_codes      VARCHAR(500),
    policy_ref        VARCHAR(120),
    rule_version      VARCHAR(20)  NOT NULL,
    trace             JSONB        NOT NULL,
    CONSTRAINT ux_decision_trace UNIQUE (request_type, request_id, evaluation_number)
);
CREATE INDEX ix_trace_request ON decision_trace (request_type, request_id);

CREATE FUNCTION append_only_guard() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only (% is not permitted)', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION append_only_guard();

CREATE TRIGGER trg_decision_trace_append_only
    BEFORE UPDATE OR DELETE ON decision_trace
    FOR EACH ROW EXECUTE FUNCTION append_only_guard();
