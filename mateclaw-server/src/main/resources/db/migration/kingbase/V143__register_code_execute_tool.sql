-- V143: Register CodeExecuteTool as a built-in tool.
-- ON DUPLICATE KEY UPDATE is the MySQL idempotent upsert.
-- Converted to: ON CONFLICT DO UPDATE with EXCLUDED references (KingbaseES / PostgreSQL).
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000023, 'CodeExecuteTool', 'Code Execute', 'Execute a snippet of code (python, bash, or node) that the agent writes on the fly. Lets a documentation-only skill be acted on by running the code its instructions describe. Dangerous operations trigger approval.', 'builtin', 'codeExecuteTool', '🧑‍💻', 1, 1, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, update_time=EXCLUDED.update_time;
