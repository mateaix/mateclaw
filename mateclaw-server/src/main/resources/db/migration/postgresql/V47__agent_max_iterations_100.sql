-- V47: Bump default agents' max_iterations to 100 (hard ceiling).
--
-- The previous defaults (25 for ReAct, 20 for plan-execute) ran the LimitExceededNode
-- too eagerly on substantive multi-tool tasks (e.g. document generation with image
-- conversion). New default is 100, the hard upper bound enforced at runtime.
-- AgentGraphBuilder still clamps any per-agent override to MAX_ITERATIONS_HARD_CEILING
-- at runtime, so a user-configured 200 will be silently capped to 100.
--
-- Idempotent: only updates rows that still hold the old defaults, so user-customized
-- agents are not touched.

UPDATE mate_agent SET max_iterations = 100
WHERE id IN (1000000001, 1000000003) AND max_iterations = 25;

UPDATE mate_agent SET max_iterations = 100
WHERE id = 1000000002 AND max_iterations = 20;
