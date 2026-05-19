-- See the H2 file for context. MySQL 8.0 supports neither
-- `ADD COLUMN IF NOT EXISTS` nor `CREATE INDEX IF NOT EXISTS`, so each
-- column and index is guarded by an INFORMATION_SCHEMA check applied via a
-- prepared statement (matches the pattern in V113 / V116).
--
-- Column types: archived_at / last_activity_at use DATETIME(3) to match
-- mate_skill_usage_stat.last_loaded_at; lifecycle_state VARCHAR(16);
-- pinned TINYINT(1).

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND COLUMN_NAME = 'lifecycle_state'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_skill ADD COLUMN lifecycle_state VARCHAR(16) DEFAULT ''active''',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND COLUMN_NAME = 'pinned'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_skill ADD COLUMN pinned TINYINT(1) DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND COLUMN_NAME = 'archived_at'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_skill ADD COLUMN archived_at DATETIME(3) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND COLUMN_NAME = 'last_activity_at'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_skill ADD COLUMN last_activity_at DATETIME(3) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND INDEX_NAME = 'idx_skill_lifecycle_state'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_skill_lifecycle_state ON mate_skill (lifecycle_state)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_skill'
      AND INDEX_NAME = 'idx_skill_last_activity_at'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_skill_last_activity_at ON mate_skill (last_activity_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- One-time backfill: existing rows take their newest usage tick as the
-- activity anchor. Rows with no usage stat stay NULL and fall through to
-- create_time at query time via the anchor() helper.
UPDATE mate_skill SET last_activity_at = (
  SELECT MAX(last_loaded_at) FROM mate_skill_usage_stat s
   WHERE s.skill_name = mate_skill.name
)
WHERE last_activity_at IS NULL;

UPDATE mate_skill SET lifecycle_state = 'active' WHERE lifecycle_state IS NULL;
