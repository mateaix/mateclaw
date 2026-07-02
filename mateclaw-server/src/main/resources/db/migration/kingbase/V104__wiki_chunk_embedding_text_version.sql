-- V104: track which input format a chunk's stored embedding was generated against.
-- The embedding input builder concatenates raw title / header breadcrumb / page
-- number alongside chunk content; bumping the builder's CURRENT_INPUT_VERSION
-- forces a re-embed pass without changing the model. NULL is treated as the
-- legacy content-only format and re-embedded lazily on the next pass.
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_chunk' AND column_name = 'embedding_text_version'
    ) THEN
        ALTER TABLE mate_wiki_chunk ADD COLUMN embedding_text_version VARCHAR(32) NULL;
    END IF;
END $$;
