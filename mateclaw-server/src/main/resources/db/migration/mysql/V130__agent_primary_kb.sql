-- V129: Store the per-agent primary wiki KB on mate_agent (MySQL).
--
-- Knowledge bases remain workspace-shared; this field only chooses the
-- default KB for wiki tools when no kbName/kbId is specified.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'primary_kb_id'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN primary_kb_id BIGINT DEFAULT NULL',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND INDEX_NAME = 'idx_agent_primary_kb'
);
SET @stmt := IF(@idx_exists = 0,
    'CREATE INDEX idx_agent_primary_kb ON mate_agent(primary_kb_id)',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

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
