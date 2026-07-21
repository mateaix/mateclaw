-- V170: Agent-level workspace artifacts — persistent, cross-session file metadata.
-- Tracks every file an Agent produces (source=agent) and every file a user
-- uploads (source=user) so the WebChat consumer (ATLAS) can list the full
-- workspace file inventory across sessions. Files live at the Agent/Workspace
-- level; sessions are only a provenance label, not an ownership container.
--
-- See GitHub issue #514. The actual file bytes stay in their existing storage
-- (GeneratedFileCache for agent output, the chat-uploads dir for user uploads);
-- this table only holds the catalog/provenance metadata + the relative
-- download URL the consumer uses to fetch each file.

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

-- Primary access path: "list this agent's artifacts, newest first" (the list
-- endpoint's hot query). Composite on agent_id + deleted keeps the listing
-- index selective even when a soft-delete accumulates.
CREATE INDEX IF NOT EXISTS idx_wsa_agent ON mate_workspace_artifact(agent_id, deleted, create_time DESC);
-- Session-filtered listing and per-session artifact lookup.
CREATE INDEX IF NOT EXISTS idx_wsa_conv ON mate_workspace_artifact(conversation_id);
-- Source filtering (agent vs user) within an agent's workspace.
CREATE INDEX IF NOT EXISTS idx_wsa_source ON mate_workspace_artifact(agent_id, source, create_time DESC);
