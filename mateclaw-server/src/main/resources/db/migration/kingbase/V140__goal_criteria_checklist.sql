-- V140: Structured, checkable criteria for goals (MySQL).
--
-- See the H2 counterpart for the full rationale. MySQL has no
-- "ADD COLUMN IF NOT EXISTS", so the column is guarded with an
-- INFORMATION_SCHEMA existence check + prepared statement for idempotency.
--
-- The column holds the goal's checklist as JSON:
--   [{ "id": "C1", "text": "...", "passed": false, "evidence": "" }, ...]
-- Additive and nullable, so existing goals load unchanged (a NULL list
-- bootstraps on first evaluation).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_agent_goal' AND column_name = 'criteria'
    ) THEN
        ALTER TABLE mate_agent_goal ADD COLUMN criteria JSON NULL;
    END IF;
END $$;
