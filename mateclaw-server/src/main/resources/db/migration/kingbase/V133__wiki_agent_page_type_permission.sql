-- V133: Per-agent, per-KB, per-pageType permission for wiki tools.
-- Read permission filters retrieval/listing; write permission gates the
-- create/compile/delete/archive/enrich/transformation tools. A row with
-- page_type='*' is the agent's KB-wide default; an exact page_type row is
-- more specific and wins over '*'. Unconfigured (no rows) falls back to the
-- KB-level defaultReadPolicy stored in the KB config.

CREATE TABLE IF NOT EXISTS mate_wiki_agent_page_type_permission (
    id            BIGINT      NOT NULL PRIMARY KEY,
    agent_id      BIGINT      NOT NULL,
    kb_id         BIGINT      NOT NULL,
    page_type     VARCHAR(64) NOT NULL,
    -- SMALLINT (not BOOLEAN): the entity fields WikiAgentPageTypePermissionEntity
    -- .can{Read,Create,Update,Delete} are Integer (1/0). Vanilla PostgreSQL cannot
    -- map a BOOLEAN into a JDBC int, so these must stay integer-typed. Do not
    -- "normalize" to BOOLEAN to match the other flag columns.
    can_read      SMALLINT    NOT NULL DEFAULT 1,
    can_create    SMALLINT    NOT NULL DEFAULT 0,
    can_update    SMALLINT    NOT NULL DEFAULT 0,
    can_delete    SMALLINT    NOT NULL DEFAULT 0,
    write_policy  VARCHAR(32) NOT NULL DEFAULT 'approval_required',
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_agent_ptperm ON mate_wiki_agent_page_type_permission (agent_id, kb_id, page_type, deleted);
CREATE INDEX IF NOT EXISTS idx_wiki_ptperm_agent_kb ON mate_wiki_agent_page_type_permission (agent_id, kb_id, deleted);
