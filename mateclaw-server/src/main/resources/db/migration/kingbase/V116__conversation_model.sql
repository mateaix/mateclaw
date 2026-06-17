-- See the H2 file for context. KingbaseES (PostgreSQL) supports
-- ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS model_provider VARCHAR(64);
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS model_name VARCHAR(128);
