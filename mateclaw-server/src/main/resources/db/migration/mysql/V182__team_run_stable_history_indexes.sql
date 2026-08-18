UPDATE mate_team_run
SET create_time = COALESCE(update_time, CURRENT_TIMESTAMP)
WHERE create_time IS NULL;

ALTER TABLE mate_team_run
    MODIFY COLUMN create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_team_run'
             AND INDEX_NAME = 'idx_team_run_team_history_stable');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_team_run_team_history_stable ON mate_team_run (team_id, create_time, id)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_team_run'
             AND INDEX_NAME = 'idx_team_run_conversation_history_stable');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_team_run_conversation_history_stable ON mate_team_run (lead_conversation_id, create_time, id)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
