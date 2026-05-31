-- V136: Wiki pipeline runtime — definitions, runs, and per-step runs.
--
-- A definition is a KB-scoped processing chain triggered by a pageType event
-- (MVP: page_type_count threshold). It declares an owner_agent_id so steps run
-- under a concrete RFC-permissioned identity. A run is one execution instance;
-- its unique key (definition_id, trigger_type, trigger_subject, trigger_bucket)
-- absorbs duplicate triggers across multiple instances. Step runs record each
-- executor invocation (MVP executors: llm, skill).

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_definition (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    kb_id                 BIGINT       NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    owner_agent_id        BIGINT       NOT NULL,
    trigger_type          VARCHAR(32)  NOT NULL,
    trigger_config_json   CLOB,
    steps_json            CLOB         NOT NULL,
    dedup_window_seconds  INT          NOT NULL DEFAULT 0,
    enabled               TINYINT      NOT NULL DEFAULT 1,
    create_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted               INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_pipeline_def_name
    ON mate_wiki_pipeline_definition (kb_id, name, deleted);
CREATE INDEX IF NOT EXISTS idx_wiki_pipeline_def_trigger
    ON mate_wiki_pipeline_definition (kb_id, trigger_type, enabled, deleted);

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_run (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    definition_id       BIGINT       NOT NULL,
    kb_id               BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    trigger_type        VARCHAR(32)  NOT NULL,
    trigger_subject     VARCHAR(128) NOT NULL,
    trigger_bucket      VARCHAR(64)  NOT NULL,
    trigger_payload_json CLOB,
    input_json          CLOB,
    output_json         CLOB,
    error_message       VARCHAR(2048),
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    create_time         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT          NOT NULL DEFAULT 0
);
-- Idempotency: one run per (definition, trigger envelope). Duplicate triggers
-- across instances collide here instead of spawning parallel runs.
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_pipeline_run_dedup
    ON mate_wiki_pipeline_run (definition_id, trigger_type, trigger_subject, trigger_bucket, deleted);
CREATE INDEX IF NOT EXISTS idx_wiki_pipeline_run_def
    ON mate_wiki_pipeline_run (definition_id, status);

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_step_run (
    id             BIGINT       NOT NULL PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    step_id        VARCHAR(128) NOT NULL,
    executor       VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    input_json     CLOB,
    output_json    CLOB,
    error_message  VARCHAR(2048),
    started_at     TIMESTAMP,
    finished_at    TIMESTAMP,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_pipeline_step_run
    ON mate_wiki_pipeline_step_run (run_id, status);
