-- V154: Wiki/knowledge-base opt-out flag on mate_agent (issue #304).
-- Mirrors skills_disabled / tools_disabled. Defaults to FALSE.
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS wiki_disabled BOOLEAN NOT NULL DEFAULT FALSE;
