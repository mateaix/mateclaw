-- V170: Tool-guard audit rows carry the auto-approve resolution outcome
-- (H2 dialect). NULL means the invocation never went through the auto-grant
-- decision layer (decision was ALLOW/BLOCK, or the row predates this column).
-- Values: AUTO_GRANT / HARD_BLOCK / FORCE_HUMAN:<pattern> / SEVERITY_CRITICAL /
-- SEVERITY_CEILING:<ceiling><<actual> / UNKNOWN_WORKSPACE / NO_GRANT.

ALTER TABLE mate_tool_guard_audit_log ADD COLUMN IF NOT EXISTS auto_approve_outcome VARCHAR(64) NULL;
