-- V128: Approval resolution log table (MySQL dialect).
CREATE TABLE IF NOT EXISTS mate_approval_resolution_log (
    id              BIGINT       NOT NULL PRIMARY KEY,
    -- Nullable: HARD_BLOCK can fire before workspace resolution; see H2 migration
    -- for full rationale. Per-workspace Dashboard queries filter on workspace_id
    -- and skip null rows; the global HARD_BLOCK panel surfaces them.
    workspace_id    BIGINT       DEFAULT NULL,
    conversation_id VARCHAR(128) DEFAULT NULL,
    agent_id        VARCHAR(64)  DEFAULT NULL,
    user_id         VARCHAR(64)  DEFAULT NULL,
    tool_call_id    VARCHAR(64)  DEFAULT NULL,
    tool_name       VARCHAR(128) NOT NULL,
    max_severity    VARCHAR(16)  DEFAULT NULL,
    rule_ids        VARCHAR(512) DEFAULT NULL,
    decision_source VARCHAR(24)  NOT NULL,
    grant_id        BIGINT       DEFAULT NULL,
    pending_id      VARCHAR(32)  DEFAULT NULL,
    args_preview    VARCHAR(500) DEFAULT NULL,
    note            VARCHAR(500) DEFAULT NULL,
    create_time     DATETIME     NOT NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    KEY idx_resolution_workspace_time (workspace_id, create_time),
    KEY idx_resolution_grant (grant_id),
    KEY idx_resolution_pending (pending_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
