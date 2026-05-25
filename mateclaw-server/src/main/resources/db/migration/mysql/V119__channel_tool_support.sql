-- V100__channel_tool_support.sql (MySQL dialect)
--
-- Mirror of the H2 V100, adapted for MySQL 8.0 — no "IF NOT EXISTS"
-- on ADD COLUMN / CREATE INDEX, so we guard with INFORMATION_SCHEMA
-- + prepared-statement so this migration is idempotent (essential
-- for desktop installs that re-apply migrations after upgrade).

-- 1. Add channel_id column if missing
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_tool'
      AND COLUMN_NAME = 'channel_id'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_tool ADD COLUMN channel_id BIGINT NULL COMMENT ''owning mate_channel.id for tool_type=channel''',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. Add channel-id index if missing
SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_tool'
      AND INDEX_NAME = 'idx_mate_tool_channel'
);
SET @stmt := IF(@idx_exists = 0,
    'CREATE INDEX idx_mate_tool_channel ON mate_tool(channel_id)',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. Deduplicate same-name rows before adding the unique index — keep
--    deleted=0 first, then most recently updated, then largest id.
DELETE FROM mate_tool
WHERE id IN (
  SELECT id FROM (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY name
             ORDER BY
               CASE WHEN deleted = 0 THEN 0 ELSE 1 END,
               update_time DESC,
               id DESC
           ) AS rn
    FROM mate_tool
  ) ranked
  WHERE rn > 1
);

-- 4. Add unique index on name if missing
SET @uk_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_tool'
      AND INDEX_NAME = 'uk_mate_tool_name'
);
SET @stmt := IF(@uk_exists = 0,
    'CREATE UNIQUE INDEX uk_mate_tool_name ON mate_tool(name)',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
