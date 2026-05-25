-- Indexes for two recently-added read paths.
--
-- (1) idx_workspace_file_agent_filename — accelerates the memory search tool
--     on mate_workspace_file. The query is "agent_id = ? AND filename LIKE
--     'prefix%' AND content LIKE '%term%'"; the existing single-column
--     idx_workspace_file_agent covers only the equality, so the filename
--     prefix scan still had to walk every row for an agent that owns
--     hundreds of daily notes. The compound (agent_id, filename) lets the
--     planner narrow on both columns in one index probe.
--
-- (2) idx_async_task_conv_status — accelerates listActiveTasks(conversationId).
--     The SQL is "WHERE conversation_id = ? AND status IN ('pending',
--     'running')". A single-column idx_async_task_conv alone still forces
--     a row scan to filter on status; the compound (conversation_id, status)
--     resolves both predicates in one index seek.

CREATE INDEX IF NOT EXISTS idx_workspace_file_agent_filename
    ON mate_workspace_file (agent_id, filename);

CREATE INDEX IF NOT EXISTS idx_async_task_conv_status
    ON mate_async_task (conversation_id, status);
