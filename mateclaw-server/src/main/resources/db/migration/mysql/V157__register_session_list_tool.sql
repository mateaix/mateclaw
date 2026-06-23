-- V157: Register SessionListTool as a built-in tool.
-- Mirrors DelegateAgentTool (spawn) so the delegation tools surface together in
-- the tool picker and AvailableToolService. SessionListTool is read-only and
-- core-tier (auto-available even without this row); this row gives it a name,
-- icon and admin toggle in the UI.
-- Idempotent: ON DUPLICATE KEY UPDATE keeps the row in sync if it already exists.

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000024, 'SessionListTool', 'Sub-Agent List', 'List the live sub-agents spawned from the current conversation: id, target agent, depth, status, phase, tool-call count, elapsed time and goal. Read-only; lets a parent agent check delegated children before deciding to wait, follow up, or proceed.', 'builtin', 'sessionListTool', '🧭', TRUE, TRUE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), display_name=VALUES(display_name), description=VALUES(description), tool_type=VALUES(tool_type), bean_name=VALUES(bean_name), icon=VALUES(icon), enabled=VALUES(enabled), builtin=VALUES(builtin), update_time=VALUES(update_time), deleted=VALUES(deleted);
