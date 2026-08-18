SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_team_task'
             AND INDEX_NAME = 'idx_team_task_conversation');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_team_task_conversation ON mate_team_task (conversation_id)',
    'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
