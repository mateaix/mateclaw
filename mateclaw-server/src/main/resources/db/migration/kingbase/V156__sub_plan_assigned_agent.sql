-- V156: Per-step agent delegation for plan-execute (see H2 file for context).
-- KingbaseES (PostgreSQL-compatible) supports ADD COLUMN IF NOT EXISTS.
ALTER TABLE mate_sub_plan ADD COLUMN IF NOT EXISTS assigned_agent_id BIGINT;
