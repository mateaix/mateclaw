-- V10: 声明式 Hook 系统
-- mate_hook       hook 定义（YAML 文件或 UI 写入）
-- mate_hook_run   hook 触发审计
-- (原本命名为 V9 但与 V9__usage_cache_tokens.sql 撞号；重命名为 V10 以共存)

CREATE TABLE IF NOT EXISTS mate_hook (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    description         VARCHAR(512),
    enabled             BOOLEAN   NOT NULL DEFAULT TRUE,
    event_type          VARCHAR(64)  NOT NULL,
    match_expression    TEXT,
    action_kind         VARCHAR(32)  NOT NULL,
    action_config       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    rate_limit_per_min  INT          DEFAULT 60,
    timeout_ms          INT          DEFAULT 3000,
    source              VARCHAR(16)  DEFAULT 'db',
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hook_event_type ON mate_hook (event_type);
CREATE INDEX IF NOT EXISTS idx_hook_enabled ON mate_hook (enabled);

CREATE TABLE IF NOT EXISTS mate_hook_run (
    id           BIGINT       NOT NULL PRIMARY KEY,
    hook_id      BIGINT       NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    duration_ms  INT          DEFAULT 0,
    message      VARCHAR(512),
    created_at   TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hook_run_hook_id ON mate_hook_run (hook_id);
CREATE INDEX IF NOT EXISTS idx_hook_run_created ON mate_hook_run (created_at);
