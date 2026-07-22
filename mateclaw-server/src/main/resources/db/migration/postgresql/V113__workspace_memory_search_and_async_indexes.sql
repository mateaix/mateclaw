-- Indexes for two recently-added read paths. Mirrors the H2 file in this
-- migration set; PostgreSQL supports CREATE INDEX IF NOT EXISTS natively.
--
-- (1) idx_workspace_file_agent_filename — accelerates the memory search tool
--     on mate_workspace_file ("agent_id = ? AND filename LIKE 'prefix%' AND
--     content LIKE '%term%'").
--
-- (2) idx_async_task_conv_status — accelerates listActiveTasks(conversationId)
--     ("WHERE conversation_id = ? AND status IN ('pending', 'running')").
--     The existing single-column idx_async_task_conv left the status filter
--     to a row scan; the compound resolves both in one index seek.

CREATE INDEX IF NOT EXISTS idx_workspace_file_agent_filename ON mate_workspace_file (agent_id, filename);

CREATE INDEX IF NOT EXISTS idx_async_task_conv_status ON mate_async_task (conversation_id, status);
