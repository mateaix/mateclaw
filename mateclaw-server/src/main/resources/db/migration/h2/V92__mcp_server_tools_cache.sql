-- V92: Persist each MCP server's discovered tool list as a per-row JSON
-- snapshot so the agent edit picker can render the tools even when the
-- upstream server is briefly disconnected, and so the per-tool atomic
-- binding flow has a stable place to resolve raw tool names from the
-- prefixed callback name.
--
-- Idempotent on re-runs (Flyway's repair-on-startup applies).

ALTER TABLE mate_mcp_server ADD COLUMN IF NOT EXISTS tools_cache_json CLOB;
ALTER TABLE mate_mcp_server ADD COLUMN IF NOT EXISTS tools_cache_updated_at TIMESTAMP;
