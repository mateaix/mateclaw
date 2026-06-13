-- Optional JSON Schema column. See h2 sibling for the prose explanation.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation' AND column_name = 'output_schema'
    ) THEN
        ALTER TABLE mate_wiki_transformation ADD COLUMN output_schema TEXT DEFAULT NULL;
    END IF;
END $$;
