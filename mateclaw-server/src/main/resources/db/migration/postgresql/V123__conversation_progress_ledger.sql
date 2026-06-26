-- V100: per-conversation progress ledger (see the H2 copy for full background).
--
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS progress_ledger TEXT NULL;
