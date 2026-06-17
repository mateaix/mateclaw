-- See the H2 copy for context. KingbaseES (PostgreSQL) supports
-- ADD COLUMN IF NOT EXISTS natively.
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS webchat_session_id VARCHAR(64);
