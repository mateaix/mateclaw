-- V166: Wiki source groups (Kingbase dialect). See h2/V166 for full design notes.
-- enabled uses SMALLINT (not BOOLEAN): WikiSourceGroupEntity.enabled is Integer
-- (1/0), same convention as WikiKnowledgeBaseEntity.watcherEnabled (V146) —
-- vanilla PostgreSQL cannot map a BOOLEAN into a JDBC int.
-- scan_mode dropped and alias uniqueness added; see h2/V166 for full notes.
CREATE TABLE IF NOT EXISTS mate_wiki_source_group (
    id            BIGINT       NOT NULL PRIMARY KEY,
    kb_id         BIGINT       NOT NULL,
    workspace_id  BIGINT,
    alias         VARCHAR(128) NOT NULL,
    path          VARCHAR(512) NOT NULL,
    file_filter   VARCHAR(256),
    cron_expr     VARCHAR(64),
    enabled       SMALLINT     NOT NULL DEFAULT 1,
    last_scan_at  TIMESTAMP,
    create_time   TIMESTAMP    NOT NULL,
    update_time   TIMESTAMP    NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_sgroup_kb ON mate_wiki_source_group (kb_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_sgroup_kb_alias ON mate_wiki_source_group (kb_id, alias);
