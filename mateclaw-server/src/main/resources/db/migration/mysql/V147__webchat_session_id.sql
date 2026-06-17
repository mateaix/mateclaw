-- See the H2 file for context. MySQL 8.0 doesn't support
-- `ADD COLUMN IF NOT EXISTS`, so the existence check goes through
-- INFORMATION_SCHEMA + a prepared statement.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_conversation'
      AND COLUMN_NAME = 'webchat_session_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_conversation ADD COLUMN webchat_session_id VARCHAR(64) NULL COMMENT ''WebChat per-thread sessionId (recoverable even when conversationId hashes)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
