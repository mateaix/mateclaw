-- RFC-077 §4.1: track which user created an Agent, so members can delete
-- their own Agents without needing workspace admin role (issue #26 Bug B).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_agent' AND column_name = 'creator_user_id'
    ) THEN
        ALTER TABLE mate_agent ADD COLUMN creator_user_id BIGINT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_agent_creator_user ON mate_agent (creator_user_id);
