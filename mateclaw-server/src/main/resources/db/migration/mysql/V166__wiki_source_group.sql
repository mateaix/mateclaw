-- V166: Wiki source groups (MySQL dialect). See h2/V166 for full design notes.
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
    deleted       INT          NOT NULL DEFAULT 0,
    KEY idx_wiki_sgroup_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
