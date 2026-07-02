-- V158: Register SessionSendTool as a built-in tool.
-- Completes the spawn/send/list delegation triad in the tool picker alongside
-- DelegateAgentTool (spawn) and SessionListTool (list). Core-tier and already
-- auto-available without this row; the row only adds UI metadata.
-- Idempotent: ON DUPLICATE KEY UPDATE keeps the row in sync if it already exists.

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000025, 'SessionSendTool', 'Sub-Agent Send', 'Send a follow-up message to a sub-agent you previously delegated to, continuing its existing session (so it still remembers the earlier task) instead of starting fresh. Pass the session_id returned by delegateToAgent.', 'builtin', 'sessionSendTool', '✉️', TRUE, TRUE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), display_name=VALUES(display_name), description=VALUES(description), tool_type=VALUES(tool_type), bean_name=VALUES(bean_name), icon=VALUES(icon), enabled=VALUES(enabled), builtin=VALUES(builtin), update_time=VALUES(update_time), deleted=VALUES(deleted);
