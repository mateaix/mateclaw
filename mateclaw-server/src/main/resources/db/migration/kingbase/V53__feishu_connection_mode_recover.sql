-- V53: Recover from V52's silent no-op.
-- See h2/V53 for the full rationale. Same surgery, Kingbase-flavored.
-- Idempotent: rows already at "websocket" are skipped by the WHERE clause.

UPDATE mate_channel
SET config_json = CASE
        WHEN config_json IS NULL OR TRIM(config_json) = '' OR TRIM(config_json) = '{}' THEN
            '{"connection_mode":"websocket"}'
        WHEN POSITION('"connection_mode"' IN config_json) = 0 THEN
            CONCAT('{"connection_mode":"websocket",', SUBSTRING(config_json, 2))
        ELSE
            REPLACE(
                REPLACE(config_json,
                    '"connection_mode": "webhook"', '"connection_mode": "websocket"'),
                '"connection_mode":"webhook"', '"connection_mode":"websocket"'
            )
    END
WHERE channel_type = 'feishu'
  AND deleted = 0
  AND (
        config_json IS NULL
     OR (POSITION('"connection_mode": "websocket"' IN config_json) = 0
         AND POSITION('"connection_mode":"websocket"' IN config_json) = 0)
  );
