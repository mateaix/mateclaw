-- V35: RFC-042 §2.3 — persist skill security scan result and timestamp.
-- Until now findings lived only in SkillRuntimeStatus memory; after a restart
-- the admin page couldn't explain why a skill was blocked. These two columns
-- keep the last scan's findings (JSONB) and time so the UI can render them
-- and offer a rescan control.
--
-- MySQL has no ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guards so
-- the migration is idempotent across redeploys.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'security_scan_result'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN security_scan_result TEXT DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'security_scan_time'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN security_scan_time TIMESTAMP DEFAULT NULL;
    END IF;
END $$;
