-- Dream v2: candidate state machine fields (rfc-035 4.1.4)
-- Phase 1 writes values only; filtering enabled in Phase 2.
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_memory_recall ADD COLUMN IF NOT EXISTS review_count INT DEFAULT 0;
ALTER TABLE mate_memory_recall ADD COLUMN IF NOT EXISTS last_reviewed_at TIMESTAMP;
