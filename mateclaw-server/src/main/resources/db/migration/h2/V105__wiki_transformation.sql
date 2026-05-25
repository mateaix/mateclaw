-- Reusable user-defined prompt templates ("transformations") that run over
-- a raw material's extracted text and persist the LLM output as an artifact
-- on the knowledge base. Templates can be flagged apply_default so the
-- ingestion pipeline runs them automatically once a raw material reaches
-- the completed state. Manual / agent-tool runs are also supported.
--
-- mate_wiki_transformation       — the template (prompt + metadata)
-- mate_wiki_transformation_run   — one row per execution attempt

CREATE TABLE IF NOT EXISTS mate_wiki_transformation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- NULL = workspace-wide template available to every KB in the workspace.
    -- Non-NULL = pinned to a single KB.
    kb_id           BIGINT NULL,

    workspace_id    BIGINT NOT NULL DEFAULT 1,

    -- Short stable identifier (e.g. "risk-extract"). Used by agent tools to
    -- target a transformation without exposing numeric IDs.
    name            VARCHAR(64)  NOT NULL,

    -- Human-readable label shown in the UI.
    title           VARCHAR(255) NOT NULL,

    description     VARCHAR(1024),

    -- Prompt body. Placeholders supported by the executor:
    --   {input_text}  — extracted text of the source raw material
    --   {title}       — title of the source raw material
    prompt_template CLOB NOT NULL,

    -- When true, the executor fires this transformation automatically for
    -- every raw material that reaches completed in the matching KB.
    apply_default   BOOLEAN NOT NULL DEFAULT FALSE,

    -- Optional explicit model override. NULL = fall back to the KB-bound
    -- chat model (same routing chain WikiCompileService uses).
    model_id        BIGINT NULL,

    enabled         BOOLEAN NOT NULL DEFAULT TRUE,

    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wtr_kb   ON mate_wiki_transformation (kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wtr_ws   ON mate_wiki_transformation (workspace_id, deleted);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wtr_kb_name ON mate_wiki_transformation (kb_id, name, deleted);


CREATE TABLE IF NOT EXISTS mate_wiki_transformation_run (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,

    transformation_id  BIGINT NOT NULL,
    kb_id              BIGINT NOT NULL,
    workspace_id       BIGINT NOT NULL DEFAULT 1,

    -- Either raw_id or page_id is set; input_kind says which.
    input_kind         VARCHAR(16) NOT NULL,
    raw_id             BIGINT NULL,
    page_id            BIGINT NULL,

    -- pending | running | completed | failed
    status             VARCHAR(16) NOT NULL DEFAULT 'pending',

    -- LLM output. Treat as Markdown unless the prompt asked for JSON.
    output             CLOB,

    error              VARCHAR(2048),

    -- Model that actually produced the output (after routing).
    model_id           BIGINT NULL,

    -- apply_default | manual | agent_tool
    triggered_by       VARCHAR(32) NOT NULL DEFAULT 'manual',

    started_at         TIMESTAMP NULL,
    completed_at       TIMESTAMP NULL,
    duration_ms        BIGINT NULL,

    create_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            INT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wtrn_tr  ON mate_wiki_transformation_run (transformation_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wtrn_kb  ON mate_wiki_transformation_run (kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wtrn_raw ON mate_wiki_transformation_run (raw_id, deleted);
