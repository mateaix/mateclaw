-- V167: link raw materials to a source group (V166). See h2/V167 for notes.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'group_id'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN group_id BIGINT DEFAULT NULL;
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS idx_wiki_raw_group ON mate_wiki_raw_material (group_id);
