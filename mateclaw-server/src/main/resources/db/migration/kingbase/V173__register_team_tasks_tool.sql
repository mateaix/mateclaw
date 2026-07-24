-- V173: Register the team_tasks built-in tool (shared team task board).
-- (KingbaseES / PostgreSQL dialect)

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000904, 'TeamTasksTool', '团队任务板', '团队共享任务板：lead 用 create 建任务并指派成员（支持 blockedBy 依赖与优先级）；成员用 progress 汇报进度、comment 留言（type=blocker 时自动失败并升级给 lead）、complete 提交结果；list/get 查看看板。仅团队成员可用。', 'builtin', 'teamTasksTool', '📋', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
