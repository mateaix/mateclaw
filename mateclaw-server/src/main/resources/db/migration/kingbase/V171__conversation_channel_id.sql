-- See the H2 file for context. KingbaseES (PostgreSQL) supports
-- ADD COLUMN IF NOT EXISTS natively.
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS channel_id BIGINT;
