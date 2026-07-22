-- V155: Link a plan to the conversation/run that produced it (see H2 file for
-- context). KingbaseES (PostgreSQL-compatible) supports ADD COLUMN IF NOT EXISTS.
ALTER TABLE mate_plan ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(64);
