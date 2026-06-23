-- V157: Register SessionListTool as a built-in tool.
-- Mirrors DelegateAgentTool (spawn) so the delegation tools surface together in
-- the tool picker and AvailableToolService. SessionListTool is read-only and
-- core-tier (auto-available even without this row); this row gives it a name,
-- icon and admin toggle in the UI.
-- Idempotent: MERGE INTO updates existing rows when id matches.

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000024, 'SessionListTool', 'Sub-Agent List', 'List the live sub-agents spawned from the current conversation: id, target agent, depth, status, phase, tool-call count, elapsed time and goal. Read-only; lets a parent agent check delegated children before deciding to wait, follow up, or proceed.', 'builtin', 'sessionListTool', '🧭', TRUE, TRUE, NOW(), NOW(), 0);
