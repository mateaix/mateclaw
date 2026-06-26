-- V160: Register the desktop local-tool proxies as built-in tools so they show
-- up in the tool picker and can be bound per-agent. The agent runtime already
-- discovers these @Tool beans live (core-tier, auto-available even without a
-- row), but the picker / per-agent binding validation reads mate_tool — without
-- these rows operators cannot grant local file/shell access to agents that use
-- an explicit tool allowlist. One row per bean: the alias index resolves the
-- class simple name to every @Tool method the bean exposes, so binding
-- 'LocalFileTools' grants all five local file operations as one capability.
-- Idempotent: ON CONFLICT keeps the row in sync if it already exists.

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000026, 'LocalFileTools', 'Local File Access', 'Read/write/edit/list/stat files on the user''s local desktop machine via the desktop tunnel. Directory-whitelisted; writes and edits require native user approval.', 'builtin', 'localFileTools', '💻', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000027, 'LocalShellTool', 'Local Shell', 'Execute shell commands on the user''s local desktop machine via the desktop tunnel. Requires native user approval.', 'builtin', 'localShellTool', '🖥', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
