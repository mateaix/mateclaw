-- See the H2 file for context. MySQL 8.0 doesn't support
-- `ADD COLUMN IF NOT EXISTS`, so the existence check goes through
-- INFORMATION_SCHEMA + a prepared statement.

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_tool_guard_audit_log'
      AND COLUMN_NAME = 'auto_approve_outcome'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_tool_guard_audit_log ADD COLUMN auto_approve_outcome VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
