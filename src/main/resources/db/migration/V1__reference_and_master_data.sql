-- =====================================================================================
-- V1: reference data and organisational master data
-- Conventions
--   * Business identifiers (EMP-000001, DEP-ENG, ...) are the primary keys, so that every
--     export, API payload and audit record references stable, human-readable IDs.
--   * No column uses a wall-clock DEFAULT: timestamps are supplied by the application clock so
--     that synthetic generation stays deterministic.
-- =====================================================================================
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Atomic business-ID counters (see IdService). One row per counter key, e.g. EMP, LVE-2026, EVT.
CREATE TABLE id_counter (
    counter_key VARCHAR(60) PRIMARY KEY,
    next_value  BIGINT NOT NULL CHECK (next_value > 0)
);

-- Currencies with documented, fixed conversion rates to the reporting currency (USD).
CREATE TABLE currency (
    currency_code       VARCHAR(3)    PRIMARY KEY CHECK (currency_code ~ '^[A-Z]{3}$'),
    currency_name       VARCHAR(60)   NOT NULL,
    rate_to_reporting   NUMERIC(18,8) NOT NULL CHECK (rate_to_reporting > 0),
    rate_effective_from DATE          NOT NULL,
    rate_source         VARCHAR(160)  NOT NULL,
    supported           BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE country (
    country_code  VARCHAR(2)  PRIMARY KEY CHECK (country_code ~ '^[A-Z]{2}$'),
    country_name  VARCHAR(80) NOT NULL UNIQUE,
    currency_code VARCHAR(3)  NOT NULL REFERENCES currency (currency_code)
);

CREATE TABLE department (
    department_id    VARCHAR(40) PRIMARY KEY,
    department_name  VARCHAR(80) NOT NULL UNIQUE,
    head_employee_id VARCHAR(40),
    status           VARCHAR(12) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

CREATE TABLE employee (
    employee_id        VARCHAR(40)  PRIMARY KEY CHECK (employee_id ~ '^EMP-[0-9]{6,}$'),
    first_name         VARCHAR(80)  NOT NULL,
    last_name          VARCHAR(80)  NOT NULL,
    email              VARCHAR(160) NOT NULL,
    phone              VARCHAR(40),
    department_id      VARCHAR(40)  NOT NULL REFERENCES department (department_id),
    designation        VARCHAR(80)  NOT NULL,
    job_level          INTEGER      NOT NULL CHECK (job_level BETWEEN 1 AND 5),
    manager_id         VARCHAR(40)  REFERENCES employee (employee_id),
    country            VARCHAR(2)   NOT NULL REFERENCES country (country_code),
    location           VARCHAR(80),
    employment_type    VARCHAR(20)  NOT NULL CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACTOR', 'INTERN')),
    employment_status  VARCHAR(20)  NOT NULL CHECK (employment_status IN ('ACTIVE', 'INACTIVE', 'TERMINATED')),
    joining_date       DATE         NOT NULL,
    termination_date   DATE,
    cost_center_id     VARCHAR(40),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_employee_not_own_manager CHECK (manager_id IS NULL OR manager_id <> employee_id),
    CONSTRAINT ck_employee_dates CHECK (termination_date IS NULL OR termination_date >= joining_date),
    CONSTRAINT ck_employee_termination_consistency CHECK ((employment_status = 'TERMINATED') = (termination_date IS NOT NULL))
);
CREATE UNIQUE INDEX ux_employee_email_lower ON employee (lower(email));
CREATE INDEX ix_employee_manager ON employee (manager_id);
CREATE INDEX ix_employee_department ON employee (department_id);
CREATE INDEX ix_employee_country ON employee (country);
CREATE INDEX ix_employee_status ON employee (employment_status);

ALTER TABLE department
    ADD CONSTRAINT fk_department_head FOREIGN KEY (head_employee_id) REFERENCES employee (employee_id);

-- Organisational hierarchy guard: no self-management and no cycles, enforced inside the database.
CREATE FUNCTION check_employee_hierarchy() RETURNS trigger AS $$
DECLARE
    cur   VARCHAR;
    depth INTEGER := 0;
BEGIN
    IF NEW.manager_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.manager_id = NEW.employee_id THEN
        RAISE EXCEPTION 'Employee % cannot be their own manager', NEW.employee_id;
    END IF;
    cur := NEW.manager_id;
    WHILE cur IS NOT NULL AND depth < 100 LOOP
        IF cur = NEW.employee_id THEN
            RAISE EXCEPTION 'Organisational hierarchy cycle detected for employee %', NEW.employee_id;
        END IF;
        SELECT manager_id INTO cur FROM employee WHERE employee_id = cur;
        depth := depth + 1;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_employee_hierarchy
    BEFORE INSERT OR UPDATE OF manager_id ON employee
    FOR EACH ROW EXECUTE FUNCTION check_employee_hierarchy();

CREATE TABLE cost_center (
    cost_center_id   VARCHAR(40)  PRIMARY KEY,
    cost_center_code VARCHAR(40)  NOT NULL UNIQUE,
    cost_center_name VARCHAR(120) NOT NULL,
    department_id    VARCHAR(40)  NOT NULL REFERENCES department (department_id),
    budget_owner_id  VARCHAR(40)  REFERENCES employee (employee_id),
    country          VARCHAR(2)   NOT NULL REFERENCES country (country_code),
    status           VARCHAR(12)  NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED', 'SUSPENDED')),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_cost_center_department ON cost_center (department_id);
CREATE INDEX ix_cost_center_country ON cost_center (country);

ALTER TABLE employee
    ADD CONSTRAINT fk_employee_cost_center FOREIGN KEY (cost_center_id) REFERENCES cost_center (cost_center_id);
CREATE INDEX ix_employee_cost_center ON employee (cost_center_id);

-- Many-to-many between employees and cost centers over time (effective-date relationship).
CREATE TABLE employee_cost_center (
    assignment_id   VARCHAR(40) PRIMARY KEY,
    employee_id     VARCHAR(40) NOT NULL REFERENCES employee (employee_id),
    cost_center_id  VARCHAR(40) NOT NULL REFERENCES cost_center (cost_center_id),
    assignment_type VARCHAR(12) NOT NULL CHECK (assignment_type IN ('PRIMARY', 'SECONDARY')),
    effective_from  DATE        NOT NULL,
    effective_to    DATE,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ecc_dates CHECK (effective_to IS NULL OR effective_to >= effective_from),
    -- an employee has at most one PRIMARY cost center at any point in time
    CONSTRAINT ex_ecc_primary_no_overlap EXCLUDE USING gist (
        employee_id WITH =,
        daterange(effective_from, COALESCE(effective_to, 'infinity'::date), '[]') WITH &&
    ) WHERE (assignment_type = 'PRIMARY')
);
CREATE INDEX ix_ecc_employee ON employee_cost_center (employee_id, effective_from);
CREATE INDEX ix_ecc_cost_center ON employee_cost_center (cost_center_id);

CREATE TABLE holiday_calendar (
    calendar_id   VARCHAR(40)  PRIMARY KEY,
    country       VARCHAR(2)   NOT NULL REFERENCES country (country_code),
    calendar_year INTEGER      NOT NULL CHECK (calendar_year BETWEEN 2000 AND 2100),
    calendar_name VARCHAR(120) NOT NULL,
    CONSTRAINT ux_holiday_calendar UNIQUE (country, calendar_year)
);

CREATE TABLE holiday (
    holiday_id   VARCHAR(40)  PRIMARY KEY,
    calendar_id  VARCHAR(40)  NOT NULL REFERENCES holiday_calendar (calendar_id),
    holiday_date DATE         NOT NULL,
    holiday_name VARCHAR(120) NOT NULL,
    holiday_type VARCHAR(20)  NOT NULL CHECK (holiday_type IN ('PUBLIC', 'REGIONAL', 'COMPANY')),
    CONSTRAINT ux_holiday_calendar_date UNIQUE (calendar_id, holiday_date)
);
CREATE INDEX ix_holiday_date ON holiday (holiday_date);
