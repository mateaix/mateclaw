-- V129: Store the per-agent primary wiki KB on mate_agent.
--
-- Knowledge bases remain workspace-shared; this field only chooses the
-- default KB for wiki tools when no kbName/kbId is specified.
ALTER TABLE mate_agent ADD COLUMN IF NOT EXISTS primary_kb_id BIGINT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_primary_kb ON mate_agent(primary_kb_id);

UPDATE mate_agent a
SET primary_kb_id = (
    SELECT kb.id
    FROM mate_wiki_knowledge_base kb
    WHERE kb.agent_id = a.id
      AND (kb.workspace_id IS NULL OR kb.workspace_id = a.workspace_id)
      AND kb.deleted = 0
    ORDER BY kb.update_time DESC
    LIMIT 1
)
WHERE a.primary_kb_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM mate_wiki_knowledge_base kb
    WHERE kb.agent_id = a.id
      AND (kb.workspace_id IS NULL OR kb.workspace_id = a.workspace_id)
      AND kb.deleted = 0
  );
