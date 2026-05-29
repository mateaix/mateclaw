-- V127: Approval auto-grant table.
-- Holds user-authorized rules that let ApprovalGrantResolver bypass createPending()
-- for matching tool calls. Each row is an explicit grant with a defined scope,
-- optional tool/rule filter, and a severity ceiling. Hard-floor patterns still
-- block irrespective of any grant.
CREATE TABLE IF NOT EXISTS mate_approval_grant (
    id              BIGINT       NOT NULL PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL,
    scope_type      VARCHAR(32)  NOT NULL,     -- USER | AGENT | CONVERSATION | WORKSPACE
    scope_id        VARCHAR(64)  NOT NULL,     -- snowflake string per CLAUDE.md precision convention
    tool_name       VARCHAR(128),               -- NULL = any tool (UI requires password confirm)
    rule_id         VARCHAR(128),               -- matches GuardFinding.ruleId; NULL = any rule
    max_severity    VARCHAR(16)  NOT NULL,     -- LOW | MEDIUM | HIGH (CRITICAL rejected by API/UI)
    grant_kind      VARCHAR(24)  NOT NULL,     -- ALWAYS | UNTIL_TIMESTAMP | UNTIL_CONVERSATION_END
    expire_at       DATETIME,                   -- only when grant_kind = UNTIL_TIMESTAMP
    granted_by      BIGINT       NOT NULL,
    granted_at      DATETIME     NOT NULL,
    revoked         TINYINT      NOT NULL DEFAULT 0,
    revoked_by      BIGINT,
    revoked_at      DATETIME,
    note            VARCHAR(500),
    create_time     DATETIME     NOT NULL,
    update_time     DATETIME     NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_grant_scope
    ON mate_approval_grant(workspace_id, scope_type, scope_id, tool_name, revoked, deleted);
CREATE INDEX IF NOT EXISTS idx_grant_expire
    ON mate_approval_grant(expire_at, revoked, deleted);
