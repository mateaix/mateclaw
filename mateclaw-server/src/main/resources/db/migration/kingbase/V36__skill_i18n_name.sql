-- V36: RFC-042 §2.2 — bilingual display names for skills.
-- name stays the immutable slug / unique identifier; name_zh and
-- name_en are optional locale-specific display labels. The UI falls
-- back to name when the locale-matching column is null.
--
-- MySQL has no ADD COLUMN IF NOT EXISTS; INFORMATION_SCHEMA guards
-- make the migration idempotent across redeploys.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'name_zh'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN name_zh VARCHAR(128) DEFAULT NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'name_en'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN name_en VARCHAR(128) DEFAULT NULL;
    END IF;
END $$;

-- Backfill bilingual names for the 19 builtin skills that already exist on
-- upgraded deployments. UPDATE is idempotent — running it again is a no-op
-- since the values match. Fresh installs handle this in data-*.sql instead
-- (those rows don't exist yet when this migration runs).
UPDATE mate_skill SET name_zh = '定时任务',       name_en = 'Cron Jobs'                WHERE name = 'cron';
UPDATE mate_skill SET name_zh = '文件阅读器',     name_en = 'File Reader'              WHERE name = 'file_reader';
UPDATE mate_skill SET name_zh = '钉钉渠道接入',   name_en = 'DingTalk Channel'         WHERE name = 'dingtalk_channel_connect';
UPDATE mate_skill SET name_zh = '邮件管理',       name_en = 'Email (Himalaya)'         WHERE name = 'himalaya';
UPDATE mate_skill SET name_zh = '新闻查询',       name_en = 'News'                     WHERE name = 'news';
UPDATE mate_skill SET name_zh = 'PDF 处理',       name_en = 'PDF'                      WHERE name = 'pdf';
UPDATE mate_skill SET name_zh = 'Word 文档',      name_en = 'Word Document'            WHERE name = 'docx';
UPDATE mate_skill SET name_zh = 'PPT 演示',       name_en = 'PowerPoint'               WHERE name = 'pptx';
UPDATE mate_skill SET name_zh = 'Excel 表格',     name_en = 'Excel'                    WHERE name = 'xlsx';
UPDATE mate_skill SET name_zh = '可见浏览器',     name_en = 'Visible Browser'          WHERE name = 'browser_visible';
UPDATE mate_skill SET name_zh = '浏览器 CDP',     name_en = 'Browser CDP'              WHERE name = 'browser_cdp';
UPDATE mate_skill SET name_zh = '安装指引',       name_en = 'Setup Guidance'           WHERE name = 'guidance';
UPDATE mate_skill SET name_zh = '源码索引',       name_en = 'Source Index'             WHERE name = 'mateclaw_source_index';
UPDATE mate_skill SET name_zh = 'SQL 查询',       name_en = 'SQL Query'                WHERE name = 'sql_query';
UPDATE mate_skill SET name_zh = '乔布斯视角',     name_en = 'Steve Jobs Perspective'   WHERE name = 'steve_jobs_perspective';
UPDATE mate_skill SET name_zh = '制定计划',       name_en = 'Make Plan'                WHERE name = 'make_plan';
UPDATE mate_skill SET name_zh = '咨询智能体',     name_en = 'Chat with Agent'          WHERE name = 'chat_with_agent';
UPDATE mate_skill SET name_zh = '渠道推送',       name_en = 'Channel Push'             WHERE name = 'channel_message';
UPDATE mate_skill SET name_zh = '多智能体协作',   name_en = 'Multi-Agent Collaboration' WHERE name = 'multi_agent_collaboration';
