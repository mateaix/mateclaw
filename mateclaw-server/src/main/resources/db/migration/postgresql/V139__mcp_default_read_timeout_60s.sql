-- Raise the default per-request (read) timeout for MCP servers from 30s to 60s.
-- A 30s ceiling cut off MCP tools whose single callTool round-trip legitimately
-- runs longer (data-heavy or compute-heavy tools), surfacing as a request timeout
-- with no retry. The application layer already falls back to 60s when the column
-- is null; this aligns the schema default so the value is consistent everywhere.
-- Only changes the column default for newly inserted rows — existing rows keep
-- whatever value they were given. Idempotent.
ALTER TABLE mate_mcp_server ALTER COLUMN read_timeout_seconds SET DEFAULT 60;
