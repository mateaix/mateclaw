-- See the H2 file for context. KingbaseES (PostgreSQL) supports
-- ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_trigger ADD COLUMN IF NOT EXISTS last_error VARCHAR(2048);
ALTER TABLE mate_trigger ADD COLUMN IF NOT EXISTS last_dispatched_at TIMESTAMP NULL;
