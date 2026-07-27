-- V175: Make the four built-in preset Agents global (workspace_id = 0).
--
-- Background (issue #567): the preset Agents (General Assistant, Task Planner,
-- Reasoning Analyst, Content Studio) were seeded without workspace_id and thus
-- fell back to the column default of 1, pinning them to the default workspace.
-- Any workspace created afterwards (id >= 2) started with an empty agent list,
-- so sub-agent delegation (delegateToAgent etc.) had no target there.
--
-- Fix (B-min): model presets as global, read-only shared records identified by
-- the reserved workspace_id = 0. AgentService.listAgentsByWorkspace now returns
-- workspace_id = wsId OR workspace_id = 0, so every workspace sees the presets;
-- write paths reject mutation of workspace_id = 0 rows (single source of truth).
-- Fresh installs seed these rows at workspace_id = 0 directly (data-*.sql); this
-- migration moves the already-seeded rows on existing installs. Idempotent: the
-- "AND workspace_id <> 0" guard makes re-runs a no-op. Statement is identical
-- across MySQL / Kingbase / H2.
UPDATE mate_agent
SET workspace_id = 0, update_time = NOW()
WHERE id IN (1000000001, 1000000002, 1000000003, 1000000640)
  AND workspace_id <> 0;
