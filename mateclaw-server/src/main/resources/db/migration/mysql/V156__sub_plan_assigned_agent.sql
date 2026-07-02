-- V156: Per-step agent delegation for plan-execute (see H2 file for context).
-- MySQL 8.0 doesn't support `ADD COLUMN IF NOT EXISTS`, so the existence check
-- goes through INFORMATION_SCHEMA + a prepared statement.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_sub_plan'
      AND COLUMN_NAME = 'assigned_agent_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_sub_plan ADD COLUMN assigned_agent_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
