-- V66: Per-model capability declaration (issue #44)
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_config' AND column_name = 'modalities'
    ) THEN
        ALTER TABLE mate_model_config ADD COLUMN modalities VARCHAR(512) DEFAULT NULL;
    END IF;
END $$;
