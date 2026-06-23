-- V154: Wiki/knowledge-base opt-out flag on mate_agent.
--
-- Mirrors skills_disabled (V126) / tools_disabled. Without this column an
-- operator who wants an agent with NO knowledge base has no way to express
-- that intent: leaving the KB picker empty means "inherit workspace-wide"
-- (every KB visible), so the agent ends up ingesting every KB's context.
-- issue #304.
--
-- Defaults to FALSE so legacy agents stay bit-identical.
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS wiki_disabled BOOLEAN NOT NULL DEFAULT FALSE;
