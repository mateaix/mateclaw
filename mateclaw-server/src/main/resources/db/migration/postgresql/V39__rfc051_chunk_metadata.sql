-- V39: RFC-051 PR-1a — chunk structural metadata.
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'page_number'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN page_number INT DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'token_count'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN token_count INT DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'header_breadcrumb'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN header_breadcrumb VARCHAR(1024) DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'source_section'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN source_section VARCHAR(512) DEFAULT NULL;
    END IF;
END $$;
