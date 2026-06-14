-- V75: Per-model HTTP read timeout (RFC-03 Lane B1).
-- Lets thinking models (o1-pro, claude opus extended-thinking, qwen3-max
-- with deep reasoning) override the default 180s read timeout when their
-- p99 legitimately exceeds it. Null / zero keeps the existing global
-- default — no behavior change for existing rows.
--
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_config' AND column_name = 'request_timeout_seconds'
    ) THEN
        ALTER TABLE mate_model_config ADD COLUMN request_timeout_seconds INT DEFAULT NULL;
    END IF;
END $$;
