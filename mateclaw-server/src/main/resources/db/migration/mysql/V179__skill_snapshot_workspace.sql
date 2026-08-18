-- Scope curator restore points to their owning workspace.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_skill_snapshot'
             AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_skill_snapshot ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_skill_snapshot'
             AND INDEX_NAME = 'idx_skill_snapshot_workspace_created');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_skill_snapshot_workspace_created ON mate_skill_snapshot (workspace_id, create_time)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
