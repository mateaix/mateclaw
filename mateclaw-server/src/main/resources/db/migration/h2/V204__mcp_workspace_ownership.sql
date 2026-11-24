-- Existing servers remain shared globally; new API creates assign the request workspace.
ALTER TABLE mate_mcp_server ADD COLUMN workspace_id BIGINT;
CREATE INDEX idx_mcp_server_workspace ON mate_mcp_server (workspace_id);
