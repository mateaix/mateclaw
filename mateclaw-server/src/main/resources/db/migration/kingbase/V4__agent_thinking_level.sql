-- V4: Add default_thinking_level to mate_agent
-- Supports: off / low / medium / high / max (null = follow model default)
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_agent' AND column_name = 'default_thinking_level'
    ) THEN
        ALTER TABLE mate_agent ADD COLUMN default_thinking_level VARCHAR(32) DEFAULT NULL;
    END IF;
END $$;
