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
    expire_at       TIMESTAMP     DEFAULT NULL,
    granted_by      BIGINT       NOT NULL,
    granted_at      TIMESTAMP     NOT NULL,
    revoked         SMALLINT      NOT NULL DEFAULT 0,
    revoked_by      BIGINT       DEFAULT NULL,
    revoked_at      TIMESTAMP     DEFAULT NULL,
    note            VARCHAR(500) DEFAULT NULL,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_grant_scope ON mate_approval_grant (workspace_id, scope_type, scope_id, tool_name, revoked, deleted);
CREATE INDEX IF NOT EXISTS idx_grant_expire ON mate_approval_grant (expire_at, revoked, deleted);
