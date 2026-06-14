-- V40: RFC-051 PR-2 — page protection flag.
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'locked'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN locked SMALLINT NOT NULL DEFAULT 0;
    END IF;
END $$;
