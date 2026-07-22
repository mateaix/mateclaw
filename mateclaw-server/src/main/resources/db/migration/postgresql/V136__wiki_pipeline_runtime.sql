-- V136: Wiki pipeline runtime — definitions, runs, and per-step runs.
-- See the H2 file for design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_definition (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    kb_id                 BIGINT       NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    owner_agent_id        BIGINT       NOT NULL,
    trigger_type          VARCHAR(32)  NOT NULL,
    trigger_config_json   JSONB,
    steps_json            JSONB    NOT NULL DEFAULT '[]'::jsonb,
    dedup_window_seconds  INT          NOT NULL DEFAULT 0,
    -- SMALLINT (not BOOLEAN): WikiPipelineDefinitionEntity.enabled is Integer (1/0).
    -- Vanilla PostgreSQL cannot map a BOOLEAN into a JDBC int. Do not switch to BOOLEAN.
    enabled               SMALLINT     NOT NULL DEFAULT 1,
    create_time           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_pipeline_def_name ON mate_wiki_pipeline_definition (kb_id, name, deleted);
CREATE INDEX IF NOT EXISTS idx_wiki_pipeline_def_trigger ON mate_wiki_pipeline_definition (kb_id, trigger_type, enabled, deleted);

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_run (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    definition_id       BIGINT       NOT NULL,
    kb_id               BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    trigger_type        VARCHAR(32)  NOT NULL,
    trigger_subject     VARCHAR(128) NOT NULL,
    trigger_bucket      VARCHAR(64)  NOT NULL,
    trigger_payload_json JSONB,
    input_json          JSONB,
    output_json         JSONB,
    error_message       VARCHAR(2048),
    started_at          TIMESTAMP(3),
    finished_at         TIMESTAMP(3),
    create_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_pipeline_run_dedup ON mate_wiki_pipeline_run (definition_id, trigger_type, trigger_subject, trigger_bucket, deleted);
CREATE INDEX IF NOT EXISTS idx_wiki_pipeline_run_def ON mate_wiki_pipeline_run (definition_id, status);

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_step_run (
    id             BIGINT       NOT NULL PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    step_id        VARCHAR(128) NOT NULL,
    executor       VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    input_json     JSONB,
    output_json    JSONB,
    error_message  VARCHAR(2048),
    started_at     TIMESTAMP(3),
    finished_at    TIMESTAMP(3),
    create_time    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_pipeline_step_run ON mate_wiki_pipeline_step_run (run_id, status);
