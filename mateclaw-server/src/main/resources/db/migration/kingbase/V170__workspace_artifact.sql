-- V170: Agent-level workspace artifacts — persistent, cross-session file metadata.
-- KingbaseES / PostgreSQL dialect. See h2/V170 for design notes.

CREATE TABLE IF NOT EXISTS mate_workspace_artifact (
    id              BIGINT       NOT NULL PRIMARY KEY,
    workspace_id    BIGINT       NOT NULL,
    channel_id      BIGINT,
    agent_id        BIGINT,
    conversation_id VARCHAR(128),
    session_label   VARCHAR(128),
    tool_call_id    VARCHAR(128),
    tool_name       VARCHAR(128),
    source          VARCHAR(16)  NOT NULL,
    artifact_type   VARCHAR(32),
    name            VARCHAR(512) NOT NULL,
    mime            VARCHAR(256),
    size_bytes      BIGINT,
    storage_kind    VARCHAR(16)  NOT NULL,
    storage_ref     VARCHAR(512) NOT NULL,
    download_url    VARCHAR(512) NOT NULL,
    create_time     TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wsa_agent  ON mate_workspace_artifact(agent_id, deleted, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_wsa_conv   ON mate_workspace_artifact(conversation_id);
CREATE INDEX IF NOT EXISTS idx_wsa_source ON mate_workspace_artifact(agent_id, source, create_time DESC);
