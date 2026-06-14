-- Per-conversation pin flag. See the h2 sibling for the rationale.
-- MySQL has no ADD COLUMN IF NOT EXISTS; guard via INFORMATION_SCHEMA.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_conversation' AND column_name = 'pinned'
    ) THEN
        ALTER TABLE mate_conversation ADD COLUMN pinned INT DEFAULT 0;
    END IF;
END $$;
