-- V3: Register CronJobTool as built-in tool (RFC-003)
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000018, 'CronJobTool', 'Scheduled Tasks', 'Create, list, enable/disable, and delete scheduled tasks (cron jobs) through chat.', 'builtin', 'cronJobTool', '⏰', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, update_time=EXCLUDED.update_time;
