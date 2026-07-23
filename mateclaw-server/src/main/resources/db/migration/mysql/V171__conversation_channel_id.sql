-- See the H2 file for context. MySQL 8.0 doesn't support
-- `ADD COLUMN IF NOT EXISTS`, so the existence check goes through
-- INFORMATION_SCHEMA + a prepared statement.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_conversation'
      AND COLUMN_NAME = 'channel_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_conversation ADD COLUMN channel_id BIGINT NULL COMMENT ''WebChat channel id for exact channel-scoped /sessions filtering (#558); NULL for non-webchat and pre-fix rows''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
