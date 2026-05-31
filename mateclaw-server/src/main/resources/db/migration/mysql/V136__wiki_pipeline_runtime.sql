-- V136: Wiki pipeline runtime — definitions, runs, and per-step runs.
-- See the H2 file for design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_definition (
    id                    BIGINT       NOT NULL PRIMARY KEY,
    kb_id                 BIGINT       NOT NULL,
    name                  VARCHAR(128) NOT NULL,
    owner_agent_id        BIGINT       NOT NULL,
    trigger_type          VARCHAR(32)  NOT NULL,
    trigger_config_json   LONGTEXT,
    steps_json            LONGTEXT     NOT NULL,
    dedup_window_seconds  INT          NOT NULL DEFAULT 0,
    enabled               TINYINT      NOT NULL DEFAULT 1,
    create_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted               INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_wiki_pipeline_def_name (kb_id, name, deleted),
    KEY idx_wiki_pipeline_def_trigger (kb_id, trigger_type, enabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_run (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    definition_id       BIGINT       NOT NULL,
    kb_id               BIGINT       NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    trigger_type        VARCHAR(32)  NOT NULL,
    trigger_subject     VARCHAR(128) NOT NULL,
    trigger_bucket      VARCHAR(64)  NOT NULL,
    trigger_payload_json LONGTEXT,
    input_json          LONGTEXT,
    output_json         LONGTEXT,
    error_message       VARCHAR(2048),
    started_at          DATETIME(3),
    finished_at         DATETIME(3),
    create_time         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted             INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_wiki_pipeline_run_dedup (definition_id, trigger_type, trigger_subject, trigger_bucket, deleted),
    KEY idx_wiki_pipeline_run_def (definition_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS mate_wiki_pipeline_step_run (
    id             BIGINT       NOT NULL PRIMARY KEY,
    run_id         BIGINT       NOT NULL,
    step_id        VARCHAR(128) NOT NULL,
    executor       VARCHAR(32)  NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    input_json     LONGTEXT,
    output_json    LONGTEXT,
    error_message  VARCHAR(2048),
    started_at     DATETIME(3),
    finished_at    DATETIME(3),
    create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted        INT          NOT NULL DEFAULT 0,
    KEY idx_wiki_pipeline_step_run (run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
