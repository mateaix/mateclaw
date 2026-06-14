-- Reusable user-defined prompt templates ("transformations") that run over
-- a raw material's extracted text and persist the LLM output as an artifact
-- on the knowledge base. Templates can be flagged apply_default so the
-- ingestion pipeline runs them automatically once a raw material reaches
-- the completed state. Manual / agent-tool runs are also supported.

CREATE TABLE IF NOT EXISTS mate_wiki_transformation (
    id              BIGINT  PRIMARY KEY,

    kb_id           BIGINT NULL,
    workspace_id    BIGINT NOT NULL DEFAULT 1,

    name            VARCHAR(64)  NOT NULL,
    title           VARCHAR(255) NOT NULL,
    description     VARCHAR(1024),

    prompt_template TEXT NOT NULL,

    apply_default   SMALLINT NOT NULL DEFAULT 0,
    model_id        BIGINT NULL,
    enabled         SMALLINT NOT NULL DEFAULT 1,

    create_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wtr_kb ON mate_wiki_transformation (kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wtr_ws ON mate_wiki_transformation (workspace_id, deleted);

-- Unique name per KB (NULL kb_id rows compete in a shared "global" bucket).
-- MySQL treats NULL as distinct in unique indexes, so workspace-wide names
-- can technically collide; the service layer enforces uniqueness for the
-- NULL-kb_id case in software.
CREATE UNIQUE INDEX uk_wtr_kb_name ON mate_wiki_transformation (kb_id, name, deleted);


CREATE TABLE IF NOT EXISTS mate_wiki_transformation_run (
    id                 BIGINT  PRIMARY KEY,

    transformation_id  BIGINT NOT NULL,
    kb_id              BIGINT NOT NULL,
    workspace_id       BIGINT NOT NULL DEFAULT 1,

    input_kind         VARCHAR(16) NOT NULL,
    raw_id             BIGINT NULL,
    page_id            BIGINT NULL,

    status             VARCHAR(16) NOT NULL DEFAULT 'pending',

    output             TEXT,
    error              VARCHAR(2048),
    model_id           BIGINT NULL,

    triggered_by       VARCHAR(32) NOT NULL DEFAULT 'manual',

    started_at         TIMESTAMP(3) NULL,
    completed_at       TIMESTAMP(3) NULL,
    duration_ms        BIGINT NULL,

    create_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted            SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wtrn_tr ON mate_wiki_transformation_run (transformation_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wtrn_kb ON mate_wiki_transformation_run (kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wtrn_raw ON mate_wiki_transformation_run (raw_id, deleted);
