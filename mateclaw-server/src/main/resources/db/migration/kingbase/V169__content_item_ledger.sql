-- V169: Content Studio production hardening — content calendar / dedup ledger
-- (KingbaseES / PostgreSQL dialect). See h2/V169 for design notes.

CREATE TABLE IF NOT EXISTS mate_content_item (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NULL,
    platform           VARCHAR(16)  NOT NULL,
    topic              VARCHAR(512),
    topic_fingerprint  VARCHAR(64),
    title              VARCHAR(256),
    status             VARCHAR(16),
    external_ref       VARCHAR(256),
    preview_url        VARCHAR(512),
    create_time        TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    publish_time       TIMESTAMP    NULL,
    deleted            INT          DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_content_item_fp ON mate_content_item(platform, topic_fingerprint, create_time);

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000636, 'ContentItemTool', '内容日历', '内容日历 / 发布去重台账：check_recent 查最近 N 天某平台是否做过同题（选题前先查避免重复）；record 记录产出（含标题/预览链接/状态）；mark_published 标记为已发布。让每日定时不重复选题、发布可追溯。', 'builtin', 'contentItemTool', '🗓️', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000637, 'ComplianceScanTool', '合规扫描', '发布前服务端硬扫合规风险：广告法极限词（最/第一/唯一/国家级/100%）、微信诱导词（集赞/助力/分享解锁/关注才能看）、承诺收益、医疗功效。返回命中清单；公众号进草稿箱前对高危词硬拦截。', 'builtin', 'complianceScanTool', '🛡️', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
