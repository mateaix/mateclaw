-- V5: Add parent_conversation_id to mate_conversation for multi-agent delegation tracking
-- MySQL lacks ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS; use INFORMATION_SCHEMA guards.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_conversation' AND column_name = 'parent_conversation_id'
    ) THEN
        ALTER TABLE mate_conversation ADD COLUMN parent_conversation_id VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_conversation_parent ON mate_conversation (parent_conversation_id);
