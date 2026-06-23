-- V155: Link a plan to the conversation/run that produced it (see H2 file for
-- context). MySQL 8.0 doesn't support `ADD COLUMN IF NOT EXISTS`, so the
-- existence check goes through INFORMATION_SCHEMA + a prepared statement.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_plan'
      AND COLUMN_NAME = 'conversation_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_plan ADD COLUMN conversation_id VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
