-- V100__channel_tool_support.sql (H2 dialect)
--
-- Schema support for channel-native tools (e.g. Feishu calendar / docx
-- via the ChannelToolProvider SPI). Adds:
--   1. mate_tool.channel_id — owning channel id for tool_type='channel'
--   2. idx_mate_tool_channel — fast lookup by owning channel
--   3. uk_mate_tool_name     — name uniqueness so multi-node reconcile
--      can do DB-atomic upsert (MERGE INTO) without producing dupes
--
-- H2 supports CREATE INDEX / ALTER TABLE ... ADD COLUMN IF NOT EXISTS
-- natively, so the migration is just three statements.

ALTER TABLE mate_tool ADD COLUMN IF NOT EXISTS channel_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_mate_tool_channel ON mate_tool(channel_id);

-- Deduplicate any pre-existing same-name rows BEFORE adding the unique
-- index. Keep the most useful row per name: deleted=0 first, then most
-- recently updated, then largest id tie-break. Normal databases have
-- name already unique so this is a no-op; tail-noise from migration
-- aborts in earlier dev iterations gets cleaned out the same way.
DELETE FROM mate_tool WHERE id IN (
  SELECT id FROM (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY name
             ORDER BY
               CASE WHEN deleted = 0 THEN 0 ELSE 1 END,
               update_time DESC NULLS LAST,
               id DESC
           ) AS rn
    FROM mate_tool
  ) ranked
  WHERE rn > 1
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_mate_tool_name ON mate_tool(name);
