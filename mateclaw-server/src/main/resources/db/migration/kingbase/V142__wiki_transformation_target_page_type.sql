-- Optional target pageType for a transformation whose output_target='page'.
-- See the h2 sibling migration for the prose explanation.
-- MySQL INFORMATION_SCHEMA guard converted to plpgsql DO block.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation' AND column_name = 'target_page_type'
    ) THEN
        ALTER TABLE mate_wiki_transformation ADD COLUMN target_page_type VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;
