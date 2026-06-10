-- V143: Register CodeExecuteTool as a built-in tool.
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000023, 'CodeExecuteTool', 'Code Execute', 'Execute a snippet of code (python, bash, or node) that the agent writes on the fly. Lets a documentation-only skill be acted on by running the code its instructions describe. Dangerous operations trigger approval.', 'builtin', 'codeExecuteTool', '🧑‍💻', TRUE, TRUE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), display_name=VALUES(display_name), description=VALUES(description), update_time=VALUES(update_time);
