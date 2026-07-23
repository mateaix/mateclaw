-- See the H2 file for context. KingbaseES (PostgreSQL-compatible) supports
-- ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_tool_guard_audit_log ADD COLUMN IF NOT EXISTS auto_approve_outcome VARCHAR(64) NULL;
