-- V100: per-conversation progress ledger (see the H2 copy for full background).
--
-- MySQL idempotency: ALTER TABLE ADD COLUMN IF NOT EXISTS is not portable
-- across server versions, so guard with INFORMATION_SCHEMA + a prepared
-- statement so re-running the migration on an already-patched schema is a
-- no-op.

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_conversation'
      AND COLUMN_NAME = 'progress_ledger'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_conversation ADD COLUMN progress_ledger LONGTEXT NULL COMMENT ''Per-conversation progress ledger JSON (stepKey -> {label, status, note, updatedAt})''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
