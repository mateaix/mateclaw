-- V121__tool_disclosure_tier.sql (KingbaseES dialect)
--
-- Mirror of the H2 V121, adapted for KingbaseES (PostgreSQL-compatible).
-- PostgreSQL supports ADD COLUMN IF NOT EXISTS natively.
--
-- See the H2 file for the column semantics.

-- 1. mate_tool.disclosure_tier (default 'core')
ALTER TABLE mate_tool ADD COLUMN IF NOT EXISTS disclosure_tier VARCHAR(16) DEFAULT 'core';

-- 2. mate_mcp_server.disclosure_tier (default 'core' — MCP tools stay directly
--    callable; an admin can move a noisy server to extension)
ALTER TABLE mate_mcp_server ADD COLUMN IF NOT EXISTS disclosure_tier VARCHAR(16) DEFAULT 'core';

-- 3. Seed the heavy generative / browser tools as extension.
--    mate_tool.name stores the Java class name (not the @Tool function name).
UPDATE mate_tool
SET disclosure_tier = 'extension'
WHERE name IN ('ImageGenerateTool', 'MusicGenerateTool', 'VideoGenerateTool', 'Model3dGenerateTool', 'BrowserUseTool')
  AND (disclosure_tier IS NULL OR disclosure_tier = 'core');
