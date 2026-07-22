-- V13: Add embedding column to mate_wiki_chunk (RFC-011 Phase 2)
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'embedding'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN embedding BYTEA DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'embedding_model'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN embedding_model VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;
