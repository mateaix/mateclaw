-- V100: per-conversation progress ledger
--
-- Adds a single JSON column on mate_conversation that holds the agent's
-- structured progress notebook for the conversation: a map of stepKey to
-- {label, status, note, updatedAt}. The runtime renders a compact snapshot of
-- the ledger into the system prompt before every LLM call so the agent never
-- forgets what it has already done after a context-window trim — the symptom
-- that caused a long research task to either duplicate work or stall in
-- meta-reasoning under aggressive trimming.
--
-- Why a JSON column on mate_conversation rather than a per-step table:
--   * Ledgers are read together with the conversation row in the hot path;
--     a per-step table would require a join on every reasoning step.
--   * Cardinality is small — a typical multi-step task carries 5-15 entries.
--   * No external query needs to enumerate steps across conversations today.
--
-- NULL means "no ledger yet" — the rendered snapshot is suppressed and the
-- agent runs with the legacy system prompt unchanged.

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS progress_ledger CLOB;
