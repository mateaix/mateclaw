-- V92: Persist each MCP server's discovered tool list as a per-row JSON
-- snapshot so the agent edit picker can render the tools even when the
-- upstream server is briefly disconnected, and so the per-tool atomic
-- binding flow has a stable place to resolve raw tool names from the
-- prefixed callback name.
--
-- MySQL doesn't support `ADD COLUMN IF NOT EXISTS` natively (5.7 and most
-- 8.0 deployments), so guard each ALTER with an INFORMATION_SCHEMA lookup
-- + PREPARE/EXECUTE so re-runs become no-ops instead of failing the
-- migration. Flyway's repair-on-startup compensates for any partial
-- failure.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'mate_mcp_server'
             AND COLUMN_NAME  = 'tools_cache_json');
SET @s := IF(@c = 0,
             'ALTER TABLE mate_mcp_server ADD COLUMN tools_cache_json MEDIUMTEXT',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME   = 'mate_mcp_server'
             AND COLUMN_NAME  = 'tools_cache_updated_at');
SET @s := IF(@c = 0,
             'ALTER TABLE mate_mcp_server ADD COLUMN tools_cache_updated_at TIMESTAMP NULL',
             'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
