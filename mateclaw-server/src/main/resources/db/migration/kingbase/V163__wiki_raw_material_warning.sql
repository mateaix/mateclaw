-- V163: non-blocking warning surface on wiki raw material.
-- Some ingest sub-steps run async *after* the material is already marked
-- completed/partial — embedding (semantic search) and entity-graph extraction.
-- When they fail the material stays "completed" but is silently degraded
-- (e.g. not searchable), and previously the only trace was a server log line.
-- These columns let such a failure show as a non-blocking warning on an
-- otherwise-successful row. Mirrors the error_code/error_message pair so the
-- UI can render a localized friendly hint; NULL = no warning.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'warning_code'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN warning_code VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'warning_message'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN warning_message VARCHAR(512) DEFAULT NULL;
    END IF;
END $$;
