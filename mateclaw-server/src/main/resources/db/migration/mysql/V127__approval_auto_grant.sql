-- V127: Approval auto-grant table (MySQL dialect).
-- Idempotent: outer CREATE TABLE uses IF NOT EXISTS; inline KEY clauses
-- only execute on first creation, so re-running this migration is safe.
CREATE TABLE IF NOT EXISTS mate_approval_grant (
    id              BIGINT       NOT NULL PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL,
    scope_type      VARCHAR(32)  NOT NULL,
    scope_id        VARCHAR(64)  NOT NULL,
    tool_name       VARCHAR(128) DEFAULT NULL,
    rule_id         VARCHAR(128) DEFAULT NULL,
    max_severity    VARCHAR(16)  NOT NULL,
    grant_kind      VARCHAR(24)  NOT NULL,
    expire_at       DATETIME     DEFAULT NULL,
    granted_by      BIGINT       NOT NULL,
    granted_at      DATETIME     NOT NULL,
    revoked         TINYINT      NOT NULL DEFAULT 0,
    revoked_by      BIGINT       DEFAULT NULL,
    revoked_at      DATETIME     DEFAULT NULL,
    note            VARCHAR(500) DEFAULT NULL,
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    KEY idx_grant_scope (workspace_id, scope_type, scope_id, tool_name, revoked, deleted),
    KEY idx_grant_expire (expire_at, revoked, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
