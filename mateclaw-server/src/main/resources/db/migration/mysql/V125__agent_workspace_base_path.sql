-- V125: Add workspace_base_path column to mate_agent for Agent-level directory override.
-- Idempotent: checks INFORMATION_SCHEMA before ADD COLUMN.
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'workspace_base_path'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN workspace_base_path VARCHAR(512) DEFAULT NULL',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
