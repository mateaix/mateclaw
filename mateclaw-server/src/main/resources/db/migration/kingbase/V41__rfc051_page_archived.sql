-- V41: RFC-051 PR-7 — soft-archive flag.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'archived'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN archived SMALLINT NOT NULL DEFAULT 0;
    END IF;
END $$;
