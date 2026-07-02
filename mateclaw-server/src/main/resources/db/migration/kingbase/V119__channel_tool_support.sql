-- V100__channel_tool_support.sql (KingbaseES dialect)
--
-- Mirror of the H2 V100, adapted for KingbaseES (PostgreSQL-compatible).
-- PostgreSQL supports ADD COLUMN IF NOT EXISTS and CREATE INDEX IF NOT EXISTS
-- natively, so guards are not needed.

-- 1. Add channel_id column if missing
ALTER TABLE mate_tool ADD COLUMN IF NOT EXISTS channel_id BIGINT NULL;

-- 2. Add channel-id index if missing
CREATE INDEX IF NOT EXISTS idx_mate_tool_channel ON mate_tool (channel_id);

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
CREATE UNIQUE INDEX IF NOT EXISTS uk_mate_tool_name ON mate_tool (name);
