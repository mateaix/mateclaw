-- Record per-run token usage. See h2 sibling for prose explanation.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation_run' AND column_name = 'input_tokens'
    ) THEN
        ALTER TABLE mate_wiki_transformation_run ADD COLUMN input_tokens BIGINT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation_run' AND column_name = 'output_tokens'
    ) THEN
        ALTER TABLE mate_wiki_transformation_run ADD COLUMN output_tokens BIGINT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_transformation_run' AND column_name = 'total_tokens'
    ) THEN
        ALTER TABLE mate_wiki_transformation_run ADD COLUMN total_tokens BIGINT NULL;
    END IF;
END $$;
