-- Workspace-local immutable employee snapshots. No raw agent/runtime/config credentials.
CREATE TABLE mate_custom_agent_template (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    name_key VARCHAR(255) NOT NULL,
    description CLOB,
    icon VARCHAR(255),
    agent_type VARCHAR(32),
    creator_user_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    schema_version INT NOT NULL,
    snapshot_json CLOB NOT NULL,
    CONSTRAINT uk_custom_template_workspace_name UNIQUE (workspace_id, name_key)
);
CREATE INDEX idx_custom_template_workspace ON mate_custom_agent_template(workspace_id, create_time);
