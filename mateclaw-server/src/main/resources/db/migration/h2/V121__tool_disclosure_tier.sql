-- V121__tool_disclosure_tier.sql (H2 dialect)
--
-- Progressive tool disclosure. Each tool source carries a disclosure tier:
--   core      → always advertised to the LLM
--   extension → hidden behind the "extension tools" catalog until the model
--               calls enable_tool, which activates it for the rest of the
--               conversation
--
-- Tier is stored per source:
--   mate_tool.disclosure_tier        — builtin / channel atomic tools (admin
--                                       override; sensible defaults also live
--                                       in code so a not-yet-seeded tool is
--                                       still classified correctly)
--   mate_mcp_server.disclosure_tier  — one tier for the whole MCP server's
--                                       tool group. Defaults to core so MCP
--                                       tools stay directly callable; an admin
--                                       can move a noisy server to extension.
--
-- H2 supports ALTER TABLE ... ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_tool ADD COLUMN IF NOT EXISTS disclosure_tier VARCHAR(16) DEFAULT 'core';
ALTER TABLE mate_mcp_server ADD COLUMN IF NOT EXISTS disclosure_tier VARCHAR(16) DEFAULT 'core';

-- Seed the heavy generative / browser tools as extension so a customer-service
-- agent bound to dozens of tools doesn't pay their JSON-Schema cost up front.
-- NOTE: mate_tool.name stores the Java class name (not the @Tool function name).
UPDATE mate_tool
SET disclosure_tier = 'extension'
WHERE name IN ('ImageGenerateTool', 'MusicGenerateTool', 'VideoGenerateTool', 'Model3dGenerateTool', 'BrowserUseTool')
  AND (disclosure_tier IS NULL OR disclosure_tier = 'core');
