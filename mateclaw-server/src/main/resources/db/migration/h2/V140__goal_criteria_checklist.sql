-- V140: Structured, checkable criteria for goals (H2).
--
-- Adds a nullable JSON-text column holding the goal's checklist:
--   [{ "id": "C1", "text": "...", "passed": false, "evidence": "" }, ...]
-- Completion is derived from "all criteria passed" rather than a fuzzy
-- completion_score threshold. The column is additive and nullable, so
-- existing goals load unchanged (a NULL list bootstraps on first evaluation).
ALTER TABLE mate_agent_goal ADD COLUMN IF NOT EXISTS criteria CLOB;
