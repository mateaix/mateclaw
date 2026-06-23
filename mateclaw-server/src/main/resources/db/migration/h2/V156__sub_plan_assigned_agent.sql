-- V156: Per-step agent delegation for plan-execute.
--
-- A plan step can now be delegated to a dedicated specialist agent (e.g. a test
-- step handed to a "QA agent", a UI step to a "frontend agent"). assigned_agent_id
-- records which agent should run the step; the executor routes that step to the
-- delegated agent instead of the parent agent.
--
-- Nullable: a NULL assigned_agent_id means "run with the parent (plan) agent",
-- which is the original behavior — legacy rows and unassigned steps are unaffected.
ALTER TABLE mate_sub_plan ADD COLUMN IF NOT EXISTS assigned_agent_id BIGINT;
