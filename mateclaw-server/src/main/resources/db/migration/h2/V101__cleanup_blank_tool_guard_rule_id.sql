-- Earlier releases let the rule-create API persist a custom guard rule
-- with a blank rule_id because the service skipped the not-blank check.
-- The resulting row was undeletable from the UI: the delete endpoint is
-- /guard/rules/{ruleId}, and a blank path variable produces a 404 instead
-- of resolving to the row. This migration does two things:
--
--   1. Purge any orphan rows already persisted on existing installations
--      so users who hit the bug on v1.2.0 can recover without direct DB
--      surgery. NULL, empty, and whitespace-only rule_id are all swept;
--      built-in rules are excluded defensively because they are seeded
--      with stable IDs and should never appear here.
--
--   2. Add a CHECK constraint so the database itself rejects blank
--      rule_id going forward. The service-layer guard already prevents
--      this from the UI, but the DB constraint defends against any
--      future code path that bypasses the service (batch import, direct
--      SQL, future endpoints) and makes the invariant explicit at the
--      schema level.

DELETE FROM mate_tool_guard_rule
WHERE (rule_id IS NULL OR LENGTH(TRIM(rule_id)) = 0)
  AND (builtin IS NULL OR builtin = FALSE);

ALTER TABLE mate_tool_guard_rule
    ADD CONSTRAINT ck_tool_guard_rule_id_nonblank
    CHECK (rule_id IS NOT NULL AND LENGTH(TRIM(rule_id)) > 0);
