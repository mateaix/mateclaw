ALTER TABLE mate_conversation
    ADD COLUMN IF NOT EXISTS conversation_kind VARCHAR(32) NOT NULL DEFAULT 'primary';
