-- See h2/V65 for rationale. MySQL syntax differs only in the string
-- concatenation operator (CONCAT vs ||).

INSERT INTO mate_conversation
    (id, conversation_id, title, agent_id, username, message_count,
     last_message, last_active_time, stream_status, workspace_id,
     parent_conversation_id, create_time, update_time, deleted)
SELECT
    1000200000 + ws.id,
    CONCAT('tasks_', ws.id),
    '📋 定时任务',
    NULL,
    'system',
    0,
    NULL,
    NOW(),
    'idle',
    ws.id,
    NULL,
    NOW(),
    NOW(),
    0
FROM mate_workspace ws
WHERE ws.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM mate_conversation c
      WHERE c.conversation_id = CONCAT('tasks_', ws.id) AND c.deleted = 0
  );
