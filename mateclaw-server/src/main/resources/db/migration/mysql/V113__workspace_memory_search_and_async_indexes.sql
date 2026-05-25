-- Indexes for two recently-added read paths. Mirrors the H2 file in this
-- migration set; MySQL < 8.0.29 has no CREATE INDEX IF NOT EXISTS, so each
-- index is guarded by an INFORMATION_SCHEMA.STATISTICS check and applied via
-- a prepared statement when missing (matches the pattern in V69, V83, V93).
--
-- (1) idx_workspace_file_agent_filename — accelerates the memory search tool
--     on mate_workspace_file ("agent_id = ? AND filename LIKE 'prefix%' AND
--     content LIKE '%term%'"). A 64-char filename prefix is used because the
--     full filename column is VARCHAR(256); under utf8mb4 the full column
--     would chew through too much of the 3072-byte composite-key budget for
--     no real benefit (every memory filename fits within 64 chars).
--
-- (2) idx_async_task_conv_status — accelerates listActiveTasks(conversationId)
--     ("WHERE conversation_id = ? AND status IN ('pending', 'running')").
--     The existing single-column idx_async_task_conv left the status filter
--     to a row scan; the compound resolves both in one index seek.

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_workspace_file'
      AND INDEX_NAME = 'idx_workspace_file_agent_filename'
);
SET @stmt := IF(@idx_exists = 0,
    'CREATE INDEX idx_workspace_file_agent_filename ON mate_workspace_file (agent_id, filename(64))',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_async_task'
      AND INDEX_NAME = 'idx_async_task_conv_status'
);
SET @stmt := IF(@idx_exists = 0,
    'CREATE INDEX idx_async_task_conv_status ON mate_async_task (conversation_id, status)',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
