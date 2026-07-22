-- V92: Persist each MCP server's discovered tool list as a per-row JSON
-- snapshot so the agent edit picker can render the tools even when the
-- upstream server is briefly disconnected, and so the per-tool atomic
-- binding flow has a stable place to resolve raw tool names from the
-- prefixed callback name.
--
-- MySQL doesn't support ADD COLUMN IF NOT EXISTS natively (5.7 and most
-- 8.0 deployments), so guard each ALTER with an INFORMATION_SCHEMA lookup
-- + PREPARE/EXECUTE so re-runs become no-ops instead of failing the
-- migration. Flyway's repair-on-startup compensates for any partial
-- failure.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_mcp_server' AND column_name = 'tools_cache_json'
    ) THEN
        ALTER TABLE mate_mcp_server ADD COLUMN tools_cache_json JSONB;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_mcp_server' AND column_name = 'tools_cache_updated_at'
    ) THEN
        ALTER TABLE mate_mcp_server ADD COLUMN tools_cache_updated_at TIMESTAMP NULL;
    END IF;
END $$;
