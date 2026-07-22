-- V53: Recover from V52's silent no-op.
-- See h2/V53 for the full rationale. Same intent, PostgreSQL JSONB flavour.
--
-- The kingbase/h2/mysql trees treat config_json as TEXT and do string surgery
-- (TRIM / POSITION / SUBSTRING / CONCAT / REPLACE) to inject or rewrite the
-- connection_mode key. On PostgreSQL config_json is JSONB (this tree only), so
-- those text functions don't apply. The JSONB merge operator `||` sets the key
-- in one step (right side wins), COALESCE handles NULL, and the WHERE clause is
-- idempotent: rows already at "websocket" are skipped.
UPDATE mate_channel
SET config_json = COALESCE(config_json, '{}'::jsonb) || '{"connection_mode":"websocket"}'::jsonb
WHERE channel_type = 'feishu'
  AND deleted = 0
  AND config_json->>'connection_mode' IS DISTINCT FROM 'websocket';
