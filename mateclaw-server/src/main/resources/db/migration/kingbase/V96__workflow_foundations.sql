-- V96: Foundational schema for the workflow runtime.
-- Eight tables establish workflow identity (workflow + immutable revisions),
-- run state (run + per-step rows + durable pause rows for await_approval),
-- payload URI storage with inline / filesystem fallback, and trigger
-- definitions paired with a dedup-window table for envelope-based event
-- governance. CREATE TABLE IF NOT EXISTS is itself idempotent on MySQL.

-- 1. Stable workflow identity + draft (1:1 with workflow row).
CREATE TABLE IF NOT EXISTS mate_workflow (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    workspace_id          BIGINT       NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    description           VARCHAR(1024),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    draft_json            TEXT,
    draft_schema_version  VARCHAR(8),
    draft_updated_by      BIGINT,
    draft_updated_at      TIMESTAMP(3),
    latest_revision_id    BIGINT,
    created_by            BIGINT,
    create_time           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted               INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_workspace_name ON mate_workflow (workspace_id, name, deleted);

-- 2. Immutable published revisions; integer revision is monotonic per workflow.
CREATE TABLE IF NOT EXISTS mate_workflow_revision (
    id              BIGINT       NOT NULL PRIMARY KEY,
    workflow_id     BIGINT       NOT NULL,
    revision        INT          NOT NULL,
    graph_json      TEXT   NOT NULL,
    schema_version  VARCHAR(8)   NOT NULL,
    published_note  VARCHAR(512),
    published_by    BIGINT,
    create_time     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_revision ON mate_workflow_revision (workflow_id, revision);

-- 3. Workflow run instance; payload bodies live behind URIs in mate_workflow_payload.
CREATE TABLE IF NOT EXISTS mate_workflow_run (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workflow_id        BIGINT       NOT NULL,
    revision_id        BIGINT       NOT NULL,
    workspace_id       BIGINT       NOT NULL,
    state              VARCHAR(16)  NOT NULL,
    triggered_by       VARCHAR(32),
    triggered_meta     TEXT,
    initial_input_ref  VARCHAR(256),
    final_output_ref   VARCHAR(256),
    error_message      VARCHAR(2048),
    started_at         TIMESTAMP(3),
    completed_at       TIMESTAMP(3),
    create_time        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_workflow_run_started ON mate_workflow_run (workflow_id, started_at);

-- 4. Per-step run row; iteration_index reserved for fan_out (and future loop).
CREATE TABLE IF NOT EXISTS mate_workflow_run_step (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    run_id              BIGINT       NOT NULL,
    step_index          INT          NOT NULL,
    iteration_index     INT,
    step_name           VARCHAR(128),
    agent_id            BIGINT,
    state               VARCHAR(16),
    input_ref           VARCHAR(256),
    output_ref          VARCHAR(256),
    output_summary      VARCHAR(512),
    output_content_type VARCHAR(64),
    error_message       VARCHAR(2048),
    duration_ms         BIGINT,
    token_input         INT,
    token_output        INT,
    started_at          TIMESTAMP(3),
    completed_at        TIMESTAMP(3)
);
CREATE INDEX IF NOT EXISTS idx_workflow_run_step ON mate_workflow_run_step (run_id, step_index, iteration_index);

-- 5. Durable pause rows so await_approval can resume across restarts.
CREATE TABLE IF NOT EXISTS mate_workflow_run_pause (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    run_id               BIGINT       NOT NULL,
    step_id              BIGINT       NOT NULL,
    pause_kind           VARCHAR(32)  NOT NULL,
    pause_token          VARCHAR(128) NOT NULL,
    external_approval_id BIGINT,
    paused_at            TIMESTAMP(3)  NOT NULL,
    resume_deadline      TIMESTAMP(3),
    resume_payload_ref   VARCHAR(256),
    resumed_at           TIMESTAMP(3),
    resume_outcome       VARCHAR(32)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_pause_run_step ON mate_workflow_run_pause (run_id, step_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_pause_token ON mate_workflow_run_pause (pause_token);
CREATE INDEX IF NOT EXISTS idx_workflow_pause_external_approval ON mate_workflow_run_pause (external_approval_id);
CREATE INDEX IF NOT EXISTS idx_workflow_pause_open_deadline ON mate_workflow_run_pause (resumed_at, resume_deadline);

-- 6. Payload URI storage. Inline BYTEA for < 256KB; storage_kind=fs/s3/oss
-- carries the external object key in storage_ref.
CREATE TABLE IF NOT EXISTS mate_workflow_payload (
    id            BIGINT       NOT NULL PRIMARY KEY,
    payload_uri   VARCHAR(256) NOT NULL,
    workspace_id  BIGINT       NOT NULL,
    content_bytes BYTEA,
    storage_kind  VARCHAR(16)  NOT NULL,
    storage_ref   VARCHAR(512),
    content_type  VARCHAR(64),
    sha256        CHAR(64),
    size_bytes    BIGINT,
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workflow_payload_uri ON mate_workflow_payload (payload_uri);
CREATE INDEX IF NOT EXISTS idx_workflow_payload_workspace_created ON mate_workflow_payload (workspace_id, created_at);

-- 7. Trigger definitions. pattern_version is a lamport counter that fire
-- callbacks compare against on every fire to detect that another instance
-- has updated the cron expression and self-cancel the local schedule.
CREATE TABLE IF NOT EXISTS mate_trigger (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    workspace_id        BIGINT       NOT NULL,
    name                VARCHAR(128),
    pattern_type        VARCHAR(32)  NOT NULL,
    pattern_json        TEXT   NOT NULL,
    target_type         VARCHAR(16)  NOT NULL,
    target_id           BIGINT       NOT NULL,
    payload_template    TEXT,
    rate_limit_per_min  INT          NOT NULL DEFAULT 60,
    dedup_window_secs   INT          NOT NULL DEFAULT 60,
    bot_self_filter     BOOLEAN      NOT NULL DEFAULT TRUE,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    fire_count          BIGINT       NOT NULL DEFAULT 0,
    max_fires           BIGINT       NOT NULL DEFAULT 0,
    last_fired_at       TIMESTAMP(3),
    pattern_version     BIGINT       NOT NULL DEFAULT 1,
    create_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted             INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_trigger_workspace_enabled ON mate_trigger (workspace_id, enabled, deleted);
CREATE INDEX IF NOT EXISTS idx_trigger_target ON mate_trigger (target_type, target_id);

-- 8. Event dedup window. dedup_key is envelope.eventId, falling back to
-- sourceHash when the upstream channel did not provide a stable id.
CREATE TABLE IF NOT EXISTS mate_trigger_event (
    id          BIGINT       NOT NULL PRIMARY KEY,
    trigger_id  BIGINT       NOT NULL,
    dedup_key   VARCHAR(128) NOT NULL,
    received_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP(3)  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_trigger_dedup ON mate_trigger_event (trigger_id, dedup_key);
CREATE INDEX IF NOT EXISTS idx_trigger_event_expires ON mate_trigger_event (expires_at);
