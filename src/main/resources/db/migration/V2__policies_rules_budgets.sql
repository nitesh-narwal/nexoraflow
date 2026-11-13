-- =====================================================================================
-- V2: configurable policies, approval rules, budgets and policy assignments
-- All business rules that change with policy live in these tables, not in Java code.
-- =====================================================================================

CREATE TABLE leave_policy (
    leave_policy_id              VARCHAR(40)   PRIMARY KEY,
    policy_name                  VARCHAR(120)  NOT NULL,
    leave_type                   VARCHAR(20)   NOT NULL CHECK (leave_type IN ('ANNUAL', 'SICK', 'UNPAID', 'PARENTAL')),
    country                      VARCHAR(2)    REFERENCES country (country_code),   -- NULL = applies to every country
    annual_entitlement           NUMERIC(6,2)  NOT NULL CHECK (annual_entitlement >= 0),
    accrual_frequency            VARCHAR(20)   NOT NULL CHECK (accrual_frequency IN ('ANNUAL_UPFRONT', 'MONTHLY')),
    carry_forward_limit          NUMERIC(6,2)  NOT NULL DEFAULT 0 CHECK (carry_forward_limit >= 0),
    maximum_consecutive_days     INTEGER       NOT NULL CHECK (maximum_consecutive_days > 0),
    minimum_notice_days          INTEGER       NOT NULL DEFAULT 0 CHECK (minimum_notice_days >= 0),
    requires_documentation       BOOLEAN       NOT NULL DEFAULT FALSE,
    documentation_threshold_days INTEGER       NOT NULL DEFAULT 0 CHECK (documentation_threshold_days >= 0),
    eligible_employment_types    VARCHAR(100)  NOT NULL,
    effective_from               DATE          NOT NULL,
    effective_to                 DATE,
    policy_status                VARCHAR(12)   NOT NULL CHECK (policy_status IN ('ACTIVE', 'INACTIVE', 'DRAFT')),
    created_at                   TIMESTAMPTZ   NOT NULL,
    updated_at                   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_leave_policy_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- two ACTIVE versions of the same policy must never be effective at the same time
    CONSTRAINT ex_leave_policy_no_overlap EXCLUDE USING gist (
        leave_type WITH =,
        (COALESCE(country, '**')) WITH =,
        daterange(effective_from, COALESCE(effective_to, 'infinity'::date), '[]') WITH &&
    ) WHERE (policy_status = 'ACTIVE')
);

CREATE TABLE expense_policy (
    expense_policy_id        VARCHAR(40)   PRIMARY KEY,
    policy_name              VARCHAR(120)  NOT NULL,
    expense_category         VARCHAR(30)   NOT NULL CHECK (expense_category IN
                                 ('TRAVEL', 'MEALS', 'LODGING', 'OFFICE_SUPPLIES', 'TRAINING', 'CLIENT_ENTERTAINMENT')),
    country                  VARCHAR(2)    NOT NULL REFERENCES country (country_code),
    employee_type            VARCHAR(20)   NOT NULL CHECK (employee_type IN ('ALL', 'FULL_TIME', 'PART_TIME', 'CONTRACTOR', 'INTERN')),
    currency                 VARCHAR(3)    NOT NULL REFERENCES currency (currency_code),
    maximum_per_transaction  NUMERIC(18,2) NOT NULL CHECK (maximum_per_transaction > 0),
    maximum_daily_amount     NUMERIC(18,2) NOT NULL CHECK (maximum_daily_amount > 0),
    maximum_monthly_amount   NUMERIC(18,2) NOT NULL CHECK (maximum_monthly_amount > 0),
    receipt_required         BOOLEAN       NOT NULL,
    receipt_threshold        NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (receipt_threshold >= 0),
    requires_manager_approval BOOLEAN      NOT NULL DEFAULT TRUE,
    effective_from           DATE          NOT NULL,
    effective_to             DATE,
    policy_status            VARCHAR(12)   NOT NULL CHECK (policy_status IN ('ACTIVE', 'INACTIVE', 'DRAFT')),
    created_at               TIMESTAMPTZ   NOT NULL,
    updated_at               TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_expense_policy_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_expense_policy_limits CHECK (maximum_daily_amount >= maximum_per_transaction
                                               AND maximum_monthly_amount >= maximum_daily_amount),
    CONSTRAINT ex_expense_policy_no_overlap EXCLUDE USING gist (
        expense_category WITH =,
        country WITH =,
        employee_type WITH =,
        daterange(effective_from, COALESCE(effective_to, 'infinity'::date), '[]') WITH &&
    ) WHERE (policy_status = 'ACTIVE')
);
CREATE INDEX ix_expense_policy_lookup ON expense_policy (expense_category, country, effective_from);

-- Approval authority rules. For EXPENSE the amount range is in USD-equivalent (reporting currency);
-- for LEAVE it is the calculated number of business days. Range convention: minimum < amount <= maximum.
CREATE TABLE approval_rule (
    approval_rule_id      VARCHAR(40)   PRIMARY KEY,
    workflow_type         VARCHAR(10)   NOT NULL CHECK (workflow_type IN ('LEAVE', 'EXPENSE')),
    country               VARCHAR(2)    REFERENCES country (country_code),
    department_id         VARCHAR(40)   REFERENCES department (department_id),
    expense_category      VARCHAR(30)   CHECK (expense_category IN
                                 ('TRAVEL', 'MEALS', 'LODGING', 'OFFICE_SUPPLIES', 'TRAINING', 'CLIENT_ENTERTAINMENT')),
    minimum_amount        NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (minimum_amount >= 0),
    maximum_amount        NUMERIC(18,2),
    amount_unit           VARCHAR(10)   NOT NULL CHECK (amount_unit IN ('USD', 'DAYS')),
    approval_level        INTEGER       NOT NULL CHECK (approval_level BETWEEN 1 AND 5),
    approver_role         VARCHAR(30)   NOT NULL CHECK (approver_role IN
                                 ('DIRECT_MANAGER', 'MANAGER', 'DEPARTMENT_HEAD', 'FINANCE_REVIEWER', 'FINANCE_HEAD')),
    approver_designation  VARCHAR(80),
    effective_from        DATE          NOT NULL,
    effective_to          DATE,
    priority              INTEGER       NOT NULL DEFAULT 100 CHECK (priority > 0),
    rule_status           VARCHAR(12)   NOT NULL CHECK (rule_status IN ('ACTIVE', 'INACTIVE')),
    description           VARCHAR(300),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_approval_rule_amount CHECK (maximum_amount IS NULL OR maximum_amount > minimum_amount),
    CONSTRAINT ck_approval_rule_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_approval_rule_unit CHECK ((workflow_type = 'LEAVE' AND amount_unit = 'DAYS')
                                           OR (workflow_type = 'EXPENSE' AND amount_unit = 'USD'))
);
CREATE INDEX ix_approval_rule_lookup ON approval_rule (workflow_type, rule_status, approval_level);

-- Monthly budget per cost center. Remaining budget is NEVER stored: it is derived from source claims.
CREATE TABLE budget (
    budget_id        VARCHAR(60)   PRIMARY KEY,
    cost_center_id   VARCHAR(40)   NOT NULL REFERENCES cost_center (cost_center_id),
    fiscal_year      INTEGER       NOT NULL,
    period_start     DATE          NOT NULL,
    period_end       DATE          NOT NULL,
    allocated_amount NUMERIC(18,2) NOT NULL CHECK (allocated_amount >= 0),
    currency         VARCHAR(3)    NOT NULL REFERENCES currency (currency_code),
    commit_pending   BOOLEAN       NOT NULL DEFAULT TRUE,
    status           VARCHAR(12)   NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_budget_period CHECK (period_end >= period_start),
    CONSTRAINT ex_budget_no_overlap EXCLUDE USING gist (
        cost_center_id WITH =,
        daterange(period_start, period_end, '[]') WITH &&
    )
);
CREATE INDEX ix_budget_cost_center_period ON budget (cost_center_id, period_start);

-- Which leave policies apply to which employee, and when (eligibility record).
CREATE TABLE employee_policy_assignment (
    assignment_id   VARCHAR(40) PRIMARY KEY,
    employee_id     VARCHAR(40) NOT NULL REFERENCES employee (employee_id),
    leave_policy_id VARCHAR(40) NOT NULL REFERENCES leave_policy (leave_policy_id),
    effective_from  DATE        NOT NULL,
    effective_to    DATE,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_epa_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ex_epa_no_overlap EXCLUDE USING gist (
        employee_id WITH =,
        leave_policy_id WITH =,
        daterange(effective_from, COALESCE(effective_to, 'infinity'::date), '[]') WITH &&
    )
);
CREATE INDEX ix_epa_employee ON employee_policy_assignment (employee_id);
CREATE INDEX ix_epa_policy ON employee_policy_assignment (leave_policy_id);
