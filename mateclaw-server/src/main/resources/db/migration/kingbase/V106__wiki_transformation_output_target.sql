-- Two-part follow-up to V105 so a transformation's output can flow back
-- into the KB as a first-class artifact. See the h2 sibling migration for
-- the prose explanation. MySQL lacks ADD COLUMN IF NOT EXISTS, so each
-- column is guarded by an INFORMATION_SCHEMA check + prepared statement.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation' AND column_name = 'output_target'
    ) THEN
        ALTER TABLE mate_wiki_transformation ADD COLUMN output_target VARCHAR(16) NOT NULL DEFAULT 'none';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation_run' AND column_name = 'output_page_id'
    ) THEN
        ALTER TABLE mate_wiki_transformation_run ADD COLUMN output_page_id BIGINT NULL;
    END IF;
END $$;
