-- V128: Approval resolution log table.
-- Single source of truth for "approval-layer final decisions":
-- USER_MANUAL / AUTO_GRANT / HARD_BLOCK / TIMEOUT. Decoupled from the existing
-- mate_tool_guard_audit_log (which records guard evaluation facts), so Dashboard
-- decision-source charts can compute clean percentages without double-counting.
CREATE TABLE IF NOT EXISTS mate_approval_resolution_log (
    id              BIGINT       NOT NULL PRIMARY KEY,
    -- Nullable: a HARD_BLOCK event can fire before WorkspaceLookupCache has
    -- resolved a workspace (missing/deleted conversation, malformed context).
    -- Recording the safety event itself is more important than tying it to a
    -- workspace; per-workspace Dashboard panels filter with `workspace_id = ?`
    -- and naturally skip these rows, while the global "recent HARD_BLOCKs"
    -- panel still surfaces them.
    workspace_id    BIGINT,
    conversation_id VARCHAR(128),
    agent_id        VARCHAR(64),
    user_id         VARCHAR(64),
    tool_call_id    VARCHAR(64),                -- correlates to AssistantMessage.ToolCall.id when available
    tool_name       VARCHAR(128) NOT NULL,
    max_severity    VARCHAR(16),
    rule_ids        VARCHAR(512),               -- comma-joined list of GuardFinding ruleIds
    decision_source VARCHAR(24)  NOT NULL,      -- USER_MANUAL | AUTO_GRANT | HARD_BLOCK | TIMEOUT
    grant_id        BIGINT,                      -- non-null when decision_source = AUTO_GRANT
    pending_id      VARCHAR(32),                 -- non-null when path went through createPending()
    args_preview    VARCHAR(500),                -- first 500 chars of rawArguments (WARN log prints 200)
    note            VARCHAR(500),
    create_time     DATETIME     NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_resolution_workspace_time
    ON mate_approval_resolution_log(workspace_id, create_time);
CREATE INDEX IF NOT EXISTS idx_resolution_grant
    ON mate_approval_resolution_log(grant_id);
CREATE INDEX IF NOT EXISTS idx_resolution_pending
    ON mate_approval_resolution_log(pending_id);
