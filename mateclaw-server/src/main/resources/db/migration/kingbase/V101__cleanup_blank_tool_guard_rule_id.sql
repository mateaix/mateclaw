-- See the matching H2 file for context. This migration purges any
-- orphan rows that earlier releases persisted with a blank rule_id and
-- then installs a CHECK constraint so the schema itself rejects blank
-- rule_id, defending against any future code path that bypasses the
-- service-layer guard. CHECK constraints are enforced from MySQL 8.0.16
-- onward; this project targets MySQL 8.0+ so the constraint is live.

DELETE FROM mate_tool_guard_rule
WHERE (rule_id IS NULL OR LENGTH(TRIM(rule_id)) = 0)
  AND (builtin IS NULL OR builtin = FALSE);

ALTER TABLE mate_tool_guard_rule
    ADD CONSTRAINT ck_tool_guard_rule_id_nonblank
    CHECK (rule_id IS NOT NULL AND LENGTH(TRIM(rule_id)) > 0);
