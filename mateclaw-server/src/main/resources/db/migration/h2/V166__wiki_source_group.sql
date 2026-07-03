-- V166: Wiki source groups.
-- A knowledge base's sourceDirectory used to be one free-text blob (newline-
-- separated paths/globs, see WikiSourcePathValidator). This splits it into
-- first-class rows: each group is one path/glob with its own alias, file
-- filter, and (future) independent scan schedule. mate_wiki_raw_material.group_id
-- (V167) references this table; NULL group_id = ungrouped (legacy/manual raw).
-- workspace_id is denormalized from the owning KB for cheap cross-KB queries;
-- kb_id/workspace_id are logical FKs only, matching the rest of the wiki module
-- (no physical FK constraints on mate_wiki_raw_material.kb_id either).
CREATE TABLE IF NOT EXISTS mate_wiki_source_group (
    id            BIGINT       NOT NULL PRIMARY KEY,
    kb_id         BIGINT       NOT NULL,
    workspace_id  BIGINT,
    alias         VARCHAR(128) NOT NULL,
    path          VARCHAR(512) NOT NULL,
    file_filter   VARCHAR(256),
    scan_mode     VARCHAR(16)  NOT NULL DEFAULT 'incremental',
    cron_expr     VARCHAR(64),
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    last_scan_at  DATETIME,
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_sgroup_kb ON mate_wiki_source_group(kb_id);
