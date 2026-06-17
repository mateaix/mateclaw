-- Output format declared on the template. See h2 sibling for the prose
-- explanation. MySQL needs the INFORMATION_SCHEMA guard pattern.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation' AND column_name = 'output_format'
    ) THEN
        ALTER TABLE mate_wiki_transformation ADD COLUMN output_format VARCHAR(16) NOT NULL DEFAULT 'markdown';
    END IF;
END $$;
