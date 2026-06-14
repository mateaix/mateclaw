-- V7: Add last_processed_hash to mate_wiki_raw_material for skip-if-unchanged optimization
-- RFC-012 Change 5: when reprocessing, skip LLM pipeline if content_hash == last_processed_hash
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'last_processed_hash'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN last_processed_hash VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;
