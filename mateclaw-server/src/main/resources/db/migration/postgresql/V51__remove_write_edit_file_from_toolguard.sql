-- V51 (MySQL): see h2/V51 for full rationale.

UPDATE mate_tool_guard_config
SET guarded_tools_json = '["execute_shell_command"]'
WHERE id = 1000000001;
