-- =====================================================================================
-- V6: ground truth (separate schema), generation runs and report snapshots
-- The ground_truth schema is physically separate from the business schema. Decision engines never
-- read it; only the generator (writer) and the evaluation module (reader) touch it.
-- =====================================================================================

CREATE SCHEMA IF NOT EXISTS ground_truth;

CREATE TABLE ground_truth.label (
    scenario_id                  VARCHAR(60)  NOT NULL,
    entity_type                  VARCHAR(30)  NOT NULL,
    entity_id                    VARCHAR(60)  NOT NULL,
    issue_type                   VARCHAR(50)  NOT NULL,
    is_anomaly                   BOOLEAN      NOT NULL,
    expected_outcome             VARCHAR(30)  NOT NULL,
    expected_reason_code         VARCHAR(50),
    expected_approval_path       VARCHAR(500),
    expected_reimbursement_status VARCHAR(20) NOT NULL,
    severity                     VARCHAR(10)  NOT NULL CHECK (severity IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    explanation                  VARCHAR(1000) NOT NULL,
    evidence_type                VARCHAR(30)  NOT NULL,
    related_entity_ids           VARCHAR(500),
    business_rule_ids            VARCHAR(200),
    PRIMARY KEY (scenario_id, entity_type, entity_id)
);
CREATE INDEX ix_gt_entity ON ground_truth.label (entity_type, entity_id);
CREATE INDEX ix_gt_issue ON ground_truth.label (issue_type);

-- Wall-clock operational metadata about generator executions (excluded from logical checksums).
CREATE TABLE generation_run (
    run_id             VARCHAR(40)  PRIMARY KEY,
    command            VARCHAR(40)  NOT NULL,
    seed               BIGINT       NOT NULL,
    size_profile       VARCHAR(10)  NOT NULL,
    config             JSONB        NOT NULL,
    application_version VARCHAR(40) NOT NULL,
    rules_version      VARCHAR(20)  NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    finished_at        TIMESTAMPTZ,
    status             VARCHAR(12)  NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    current_stage      VARCHAR(60),
    row_counts         JSONB,
    logical_checksums  JSONB,
    error_message      VARCHAR(1000)
);

CREATE TABLE report_snapshot (
    report_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_type  VARCHAR(30) NOT NULL CHECK (report_type IN ('VALIDATION', 'EVALUATION', 'OPERATIONS')),
    generated_at TIMESTAMPTZ NOT NULL,
    content      JSONB       NOT NULL
);
CREATE INDEX ix_report_snapshot_type ON report_snapshot (report_type, generated_at DESC);
