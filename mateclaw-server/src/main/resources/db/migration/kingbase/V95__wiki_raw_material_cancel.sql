-- V95: cancellation flag for in-progress wiki raw material processing.
-- Lets the user request a stop on a long-running PDF analysis (e.g. when
-- the embedding model has run out of credits) without having to delete
-- the raw material. The processing pipeline checks the flag at its
-- existing abort checkpoints and bails out with a 'cancelled' status.
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use INFORMATION_SCHEMA guard instead.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'cancel_requested'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$;
