-- Page-level embedding columns. See the h2 sibling for the prose
-- explanation. MySQL lacks ADD COLUMN IF NOT EXISTS, so each column
-- guarded by an INFORMATION_SCHEMA check + prepared statement.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'embedding'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN embedding BYTEA DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'embedding_model'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN embedding_model VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'embedding_text_version'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN embedding_text_version VARCHAR(32) DEFAULT NULL;
    END IF;
END $$;
