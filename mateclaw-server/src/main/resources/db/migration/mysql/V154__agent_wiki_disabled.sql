-- V154: Wiki/knowledge-base opt-out flag on mate_agent (issue #304).
-- Mirrors skills_disabled / tools_disabled. Defaults to FALSE.
-- See the H2 file for context. MySQL 8.0 doesn't support
-- `ADD COLUMN IF NOT EXISTS`, so the existence check goes through
-- INFORMATION_SCHEMA + a prepared statement.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'wiki_disabled'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN wiki_disabled TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
