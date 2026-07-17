-- V170: Agent-level workspace artifacts — persistent, cross-session file metadata.
-- MySQL dialect (mirror of the h2 migration). See the h2 file for rationale.

CREATE TABLE IF NOT EXISTS mate_workspace_artifact (
    id              BIGINT       NOT NULL PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL,
    channel_id      BIGINT       NULL,
    agent_id        BIGINT       NULL,
    conversation_id VARCHAR(128) NULL,
    session_label   VARCHAR(128) NULL,
    tool_call_id    VARCHAR(128) NULL,
    tool_name       VARCHAR(128) NULL,
    source          VARCHAR(16)  NOT NULL,
    artifact_type   VARCHAR(32)  NULL,
    name            VARCHAR(512) NOT NULL,
    mime            VARCHAR(256) NULL,
    size_bytes      BIGINT       NULL,
    storage_kind    VARCHAR(16)  NOT NULL,
    storage_ref     VARCHAR(512) NOT NULL,
    download_url    VARCHAR(512) NOT NULL,
    create_time     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_wsa_agent  ON mate_workspace_artifact(agent_id, deleted, create_time DESC);
CREATE INDEX idx_wsa_conv   ON mate_workspace_artifact(conversation_id);
CREATE INDEX idx_wsa_source ON mate_workspace_artifact(agent_id, source, create_time DESC);
