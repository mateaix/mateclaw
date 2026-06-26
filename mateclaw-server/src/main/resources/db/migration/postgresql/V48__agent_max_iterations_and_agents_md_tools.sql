-- V48 (Kingbase): see h2/V48 for full rationale.
-- Use || instead of CONCAT() because Kingbase Oracle-compat CONCAT() only takes 2 args.

UPDATE mate_agent SET max_iterations = 100
WHERE id IN (1000000001, 1000000002, 1000000003)
  AND (max_iterations IS NULL OR max_iterations < 100);

UPDATE mate_workspace_file
SET content = REPLACE(
        content,
        '需要执行文件操作或命令时，直接调用对应的工具（如 execute_shell_command、read_file 等），不要用文本描述你要做什么。',
        '需要执行文件操作或命令时，直接调用对应的工具：' || CHR(10) ||
        '- 读文件 → read_file' || CHR(10) ||
        '- 写新文件或覆盖整个文件 → write_file（一次写完整内容，不要用 printf / heredoc / echo 拼）' || CHR(10) ||
        '- 修改已有文件局部内容 → edit_file' || CHR(10) ||
        '- 执行 shell 命令 → execute_shell_command' || CHR(10) ||
        '不要用文本描述你要做什么。'
    )
WHERE filename = 'AGENTS.md'
  AND content LIKE '%execute_shell_command、read_file%';
