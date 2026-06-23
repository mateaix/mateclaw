-- V158: Register SessionSendTool as a built-in tool.
-- Completes the spawn/send/list delegation triad in the tool picker alongside
-- DelegateAgentTool (spawn) and SessionListTool (list). Core-tier and already
-- auto-available without this row; the row only adds UI metadata.
-- Idempotent: MERGE INTO updates existing rows when id matches.

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000025, 'SessionSendTool', 'Sub-Agent Send', 'Send a follow-up message to a sub-agent you previously delegated to, continuing its existing session (so it still remembers the earlier task) instead of starting fresh. Pass the session_id returned by delegateToAgent.', 'builtin', 'sessionSendTool', '✉️', TRUE, TRUE, NOW(), NOW(), 0);
