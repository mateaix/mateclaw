-- V100: Seed coding tools into mate_tool so they appear in the UI picker
-- and can be pre-bound via agent template defaultToolNames.
-- Tools: apply_patch, glob, grep, git, git_commit
-- Ids: 1000000030..1000000033 (1000000027 was the last used id)

-- ApplyPatchTool — unified diff editor with uniqueness check
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000030, 'ApplyPatchTool', '补丁应用', '应用 unified diff 补丁到文件。每个 hunk 的上下文必须唯一匹配，否则整体回滚。比 edit_file 更适合多位置编辑。', 'builtin', 'applyPatchTool', '🔧', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

-- GlobTool — file name pattern matching (no shell-out)
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000031, 'GlobTool', '文件搜索', '按 glob 模式递归查找文件（如 **/*.java）。纯 Java PathMatcher，不执行 shell，自动跳过 .git/node_modules/target。', 'builtin', 'globTool', '🔍', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

-- GrepTool — regex content search (no shell-out)
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000032, 'GrepTool', '内容搜索', '按正则表达式递归搜索文件内容，返回匹配行及行号。支持上下文行、扩展名过滤、大小写忽略。纯 Java 实现，不执行 shell。', 'builtin', 'grepTool', '🔎', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

-- GitTool — git operations (read + write, writes go through ToolGuard)
INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000033, 'GitTool', 'Git 操作', '执行 git 命令（status/diff/log/branch/add/commit/push/pull 等）。只读操作安全，写操作触发审批确认。', 'builtin', 'gitTool', ' Git', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
