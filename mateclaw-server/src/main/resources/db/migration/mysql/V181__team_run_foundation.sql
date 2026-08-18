CREATE TABLE IF NOT EXISTS mate_team_run (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    team_id               BIGINT        NOT NULL,
    workspace_id          BIGINT        NOT NULL,
    lead_agent_id         BIGINT        NOT NULL,
    lead_conversation_id  VARCHAR(64)   NOT NULL,
    origin_message_id     BIGINT        NULL,
    title                 VARCHAR(255)  NOT NULL,
    objective             TEXT          NOT NULL,
    status                VARCHAR(32)   NOT NULL DEFAULT 'planning',
    final_summary         TEXT,
    stop_reason           VARCHAR(1000),
    metadata              TEXT,
    started_at            TIMESTAMP     NULL,
    completed_at          TIMESTAMP     NULL,
    create_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP     NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               INT           DEFAULT 0,
    KEY idx_team_run_team_history (team_id, create_time),
    KEY idx_team_run_conversation_history (lead_conversation_id, create_time),
    KEY idx_team_run_status (status, update_time),
    UNIQUE KEY uk_team_run_origin_message (workspace_id, lead_conversation_id, origin_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_team_task'
             AND COLUMN_NAME = 'run_id');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_team_task ADD COLUMN run_id BIGINT NULL AFTER team_id',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_team_task'
             AND INDEX_NAME = 'idx_team_task_run_number');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_team_task_run_number ON mate_team_task (run_id, task_number)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_team_task'
             AND INDEX_NAME = 'idx_team_task_run_status');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_team_task_run_status ON mate_team_task (run_id, status)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
