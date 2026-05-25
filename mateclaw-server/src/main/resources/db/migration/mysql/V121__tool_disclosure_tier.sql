-- V121__tool_disclosure_tier.sql (MySQL dialect)
--
-- Mirror of the H2 V121, adapted for MySQL 8.0 — no "IF NOT EXISTS" on
-- ADD COLUMN, so we guard with INFORMATION_SCHEMA + prepared statement to
-- stay idempotent (essential for desktop installs that re-apply migrations).
--
-- See the H2 file for the column semantics.

-- 1. mate_tool.disclosure_tier (default 'core')
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_tool'
      AND COLUMN_NAME = 'disclosure_tier'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_tool ADD COLUMN disclosure_tier VARCHAR(16) DEFAULT ''core'' COMMENT ''core | extension — progressive disclosure tier''',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. mate_mcp_server.disclosure_tier (default 'core' — MCP tools stay directly
--    callable; an admin can move a noisy server to extension)
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_mcp_server'
      AND COLUMN_NAME = 'disclosure_tier'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_mcp_server ADD COLUMN disclosure_tier VARCHAR(16) DEFAULT ''core'' COMMENT ''core | extension — whole-server disclosure tier''',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. Seed the heavy generative / browser tools as extension.
--    mate_tool.name stores the Java class name (not the @Tool function name).
UPDATE mate_tool
SET disclosure_tier = 'extension'
WHERE name IN ('ImageGenerateTool', 'MusicGenerateTool', 'VideoGenerateTool', 'Model3dGenerateTool', 'BrowserUseTool')
  AND (disclosure_tier IS NULL OR disclosure_tier = 'core');
