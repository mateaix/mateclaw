-- MateClaw 数据库初始化脚本（KingbaseES / PostgreSQL 兼容）

-- 用户表
CREATE TABLE IF NOT EXISTS mate_user (
    id          BIGINT       NOT NULL PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(200) NOT NULL,
    nickname    VARCHAR(64),
    avatar      VARCHAR(256),
    email       VARCHAR(128),
    role        VARCHAR(32)  NOT NULL DEFAULT 'user',
    enabled     BOOLEAN   NOT NULL DEFAULT TRUE,
    create_time TIMESTAMP     NOT NULL,
    update_time TIMESTAMP     NOT NULL,
    deleted     INT          NOT NULL DEFAULT 0
);

-- Agent 配置表
CREATE TABLE IF NOT EXISTS mate_agent (
    id             BIGINT       NOT NULL PRIMARY KEY,
    name           VARCHAR(128) NOT NULL,
    description    TEXT,
    agent_type     VARCHAR(32)  NOT NULL DEFAULT 'react',
    system_prompt  TEXT,
    model_name     VARCHAR(128),
    max_iterations INT          NOT NULL DEFAULT 10,
    enabled        BOOLEAN   NOT NULL DEFAULT TRUE,
    icon           VARCHAR(256),
    tags           VARCHAR(256),
    workspace_id   BIGINT       NOT NULL DEFAULT 1,
    create_time    TIMESTAMP     NOT NULL,
    update_time    TIMESTAMP     NOT NULL,
    deleted        INT          NOT NULL DEFAULT 0
);

-- 模型配置表
CREATE TABLE IF NOT EXISTS mate_model_config (
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    provider     VARCHAR(64)  NOT NULL DEFAULT 'dashscope',
    model_name   VARCHAR(128) NOT NULL,
    description  TEXT,
    temperature  DOUBLE PRECISION,
    max_tokens   INT,
    top_p        DOUBLE PRECISION,
    builtin      BOOLEAN   NOT NULL DEFAULT TRUE,
    enabled      BOOLEAN   NOT NULL DEFAULT TRUE,
    is_default   BOOLEAN   NOT NULL DEFAULT FALSE,
    max_input_tokens INT      DEFAULT 0,
    enable_search BOOLEAN  DEFAULT FALSE,
    search_strategy VARCHAR(32) DEFAULT NULL,
    create_time  TIMESTAMP     NOT NULL,
    update_time  TIMESTAMP     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_model_config_model_name ON mate_model_config (model_name);

-- 模型 Provider 表
CREATE TABLE IF NOT EXISTS mate_model_provider (
    provider_id                 VARCHAR(64)  NOT NULL PRIMARY KEY,
    name                        VARCHAR(128) NOT NULL,
    api_key_prefix              VARCHAR(32),
    chat_model                  VARCHAR(64),
    api_key                     VARCHAR(512),
    base_url                    VARCHAR(512),
    generate_kwargs             TEXT,
    is_custom                   BOOLEAN   NOT NULL DEFAULT FALSE,
    is_local                    BOOLEAN   NOT NULL DEFAULT FALSE,
    support_model_discovery     BOOLEAN   NOT NULL DEFAULT FALSE,
    support_connection_check    BOOLEAN   NOT NULL DEFAULT FALSE,
    freeze_url                  BOOLEAN   NOT NULL DEFAULT FALSE,
    require_api_key             BOOLEAN   NOT NULL DEFAULT TRUE,
    auth_type                   VARCHAR(16)  NOT NULL DEFAULT 'api_key',
    oauth_access_token          TEXT,
    oauth_refresh_token         TEXT,
    oauth_expires_at            BIGINT,
    oauth_account_id            VARCHAR(128),
    create_time                 TIMESTAMP     NOT NULL,
    update_time                 TIMESTAMP     NOT NULL
);

-- 系统设置表
CREATE TABLE IF NOT EXISTS mate_system_setting (
    id           BIGINT       NOT NULL PRIMARY KEY,
    setting_key  VARCHAR(128) NOT NULL UNIQUE,
    setting_value TEXT,
    description  VARCHAR(256),
    create_time  TIMESTAMP     NOT NULL,
    update_time  TIMESTAMP     NOT NULL
);

-- 技能表
CREATE TABLE IF NOT EXISTS mate_skill (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    description   TEXT,
    skill_type    VARCHAR(32)  NOT NULL DEFAULT 'dynamic',
    icon          VARCHAR(256),
    version       VARCHAR(32),
    author        VARCHAR(64),
    config_json   TEXT,
    source_code   TEXT,
    skill_content TEXT,
    enabled       BOOLEAN   NOT NULL DEFAULT TRUE,
    builtin       BOOLEAN   NOT NULL DEFAULT FALSE,
    tags          VARCHAR(256),
    workspace_id  BIGINT       NOT NULL DEFAULT 1,
    create_time   TIMESTAMP     NOT NULL,
    update_time   TIMESTAMP     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);

-- 工具表
CREATE TABLE IF NOT EXISTS mate_tool (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    display_name  VARCHAR(128),
    description   TEXT,
    tool_type     VARCHAR(32)  NOT NULL DEFAULT 'builtin',
    bean_name     VARCHAR(128),
    icon          VARCHAR(256),
    mcp_endpoint  VARCHAR(256),
    params_schema TEXT,
    enabled       BOOLEAN   NOT NULL DEFAULT TRUE,
    builtin       BOOLEAN   NOT NULL DEFAULT FALSE,
    workspace_id  BIGINT       NOT NULL DEFAULT 1,
    create_time   TIMESTAMP     NOT NULL,
    update_time   TIMESTAMP     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);

-- 渠道表
CREATE TABLE IF NOT EXISTS mate_channel (
    id           BIGINT       NOT NULL PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    channel_type VARCHAR(32)  NOT NULL,
    agent_id     BIGINT,
    bot_prefix   VARCHAR(64),
    config_json  TEXT,
    enabled      BOOLEAN   NOT NULL DEFAULT FALSE,
    description  VARCHAR(256),
    workspace_id BIGINT       NOT NULL DEFAULT 1,
    create_time  TIMESTAMP     NOT NULL,
    update_time  TIMESTAMP     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
);

-- 会话表
CREATE TABLE IF NOT EXISTS mate_conversation (
    id               BIGINT       NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL UNIQUE,
    title            VARCHAR(256),
    agent_id         BIGINT,
    username         VARCHAR(64),
    message_count    INT          NOT NULL DEFAULT 0,
    last_message     TEXT,
    last_active_time TIMESTAMP,
    stream_status    VARCHAR(16)  NOT NULL DEFAULT 'idle',
    workspace_id     BIGINT       NOT NULL DEFAULT 1,
    create_time      TIMESTAMP     NOT NULL,
    update_time      TIMESTAMP     NOT NULL,
    deleted          INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_conversation_username ON mate_conversation (username);

-- 消息表
CREATE TABLE IF NOT EXISTS mate_message (
    id              BIGINT       NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    role            VARCHAR(32)  NOT NULL,
    content         TEXT,
    content_parts   TEXT,
    tool_name       VARCHAR(128),
    token_usage     INT,
    prompt_tokens   INT          DEFAULT 0,
    completion_tokens INT        DEFAULT 0,
    runtime_model   VARCHAR(128),
    runtime_provider VARCHAR(64),
    status          VARCHAR(32)  NOT NULL DEFAULT 'completed',
    metadata        JSONB,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);
COMMENT ON COLUMN mate_message.metadata IS '存储 toolCalls, plan, currentPhase, pendingApproval 等元数据';
CREATE INDEX IF NOT EXISTS idx_message_conversation ON mate_message (conversation_id);
CREATE INDEX IF NOT EXISTS idx_message_conv_time ON mate_message (conversation_id, create_time);

-- 执行计划表
CREATE TABLE IF NOT EXISTS mate_plan (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        VARCHAR(64),
    goal            TEXT,
    status          VARCHAR(32)  NOT NULL DEFAULT 'pending',
    total_steps     INT          NOT NULL DEFAULT 0,
    completed_steps INT          NOT NULL DEFAULT 0,
    summary         TEXT,
    start_time      TIMESTAMP,
    end_time        TIMESTAMP,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

-- 子计划步骤表
CREATE TABLE IF NOT EXISTS mate_sub_plan (
    id          BIGINT       NOT NULL PRIMARY KEY,
    plan_id     BIGINT       NOT NULL,
    step_index  INT          NOT NULL,
    description TEXT,
    status      VARCHAR(32)  NOT NULL DEFAULT 'pending',
    result      TEXT,
    start_time  TIMESTAMP,
    end_time    TIMESTAMP,
    create_time TIMESTAMP     NOT NULL,
    update_time TIMESTAMP     NOT NULL,
    deleted     INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_sub_plan_plan_id ON mate_sub_plan (plan_id);

-- 定时任务表
CREATE TABLE IF NOT EXISTS mate_cron_job (
    id              BIGINT        NOT NULL PRIMARY KEY,
    name            VARCHAR(128)  NOT NULL,
    cron_expression VARCHAR(128)  NOT NULL,
    timezone        VARCHAR(64)   NOT NULL DEFAULT 'Asia/Shanghai',
    agent_id        BIGINT        NOT NULL,
    task_type       VARCHAR(16)   NOT NULL DEFAULT 'text',
    trigger_message TEXT,
    request_body    TEXT,
    enabled         BOOLEAN    NOT NULL DEFAULT TRUE,
    next_run_time   TIMESTAMP,
    last_run_time   TIMESTAMP,
    create_time     TIMESTAMP      NOT NULL,
    update_time     TIMESTAMP      NOT NULL,
    deleted         INT           NOT NULL DEFAULT 0
);

-- 渠道会话存储表
CREATE TABLE IF NOT EXISTS mate_channel_session (
    id              BIGINT       NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(128) NOT NULL UNIQUE,
    channel_type    VARCHAR(32)  NOT NULL,
    target_id       VARCHAR(512) NOT NULL,
    sender_id       VARCHAR(128),
    sender_name     VARCHAR(128),
    channel_id      BIGINT,
    last_active_time TIMESTAMP    NOT NULL,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_channel_session_type ON mate_channel_session (channel_type);
CREATE INDEX IF NOT EXISTS idx_channel_session_channel_id ON mate_channel_session (channel_id);

-- 工作区文件表（Agent 级 Markdown 文档管理）
CREATE TABLE IF NOT EXISTS mate_workspace_file (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    filename        VARCHAR(256) NOT NULL,
    content         TEXT,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    enabled         BOOLEAN   NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_workspace_file_agent ON mate_workspace_file (agent_id);
CREATE INDEX IF NOT EXISTS idx_workspace_file_agent_enabled ON mate_workspace_file (agent_id, enabled);

-- ==================== MCP Server 管理 ====================

CREATE TABLE IF NOT EXISTS mate_mcp_server (
    id                      BIGINT       NOT NULL PRIMARY KEY,
    name                    VARCHAR(128) NOT NULL,
    description             TEXT,
    transport               VARCHAR(32)  NOT NULL DEFAULT 'stdio',
    url                     VARCHAR(512),
    headers_json            TEXT,
    command                 VARCHAR(512),
    args_json               TEXT,
    env_json                TEXT,
    cwd                     VARCHAR(512),
    enabled                 BOOLEAN   NOT NULL DEFAULT TRUE,
    connect_timeout_seconds INT          NOT NULL DEFAULT 30,
    read_timeout_seconds    INT          NOT NULL DEFAULT 30,
    last_status             VARCHAR(32)  NOT NULL DEFAULT 'disconnected',
    last_error              TEXT,
    last_connected_time     TIMESTAMP,
    tool_count              INT          NOT NULL DEFAULT 0,
    builtin                 BOOLEAN   NOT NULL DEFAULT FALSE,
    create_time             TIMESTAMP     NOT NULL,
    update_time             TIMESTAMP     NOT NULL,
    deleted                 INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_mcp_server_enabled ON mate_mcp_server (enabled);

-- ==================== 工具安全治理（ToolGuard） ====================

-- 工具审批表
CREATE TABLE IF NOT EXISTS mate_tool_approval (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    pending_id          VARCHAR(32)  NOT NULL UNIQUE,
    conversation_id     VARCHAR(128) NOT NULL,
    user_id             VARCHAR(64),
    agent_id            VARCHAR(64),
    channel_type        VARCHAR(32),
    requester_name      VARCHAR(128),
    reply_target        VARCHAR(512),
    tool_name           VARCHAR(128) NOT NULL,
    tool_arguments      TEXT,
    tool_call_payload   TEXT,
    tool_call_hash      VARCHAR(64),
    sibling_tool_calls  TEXT,
    summary             TEXT,
    findings_json       TEXT,
    max_severity        VARCHAR(16),
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    resolved_by         VARCHAR(64),
    created_at          TIMESTAMP     NOT NULL,
    resolved_at         TIMESTAMP,
    expire_at           TIMESTAMP,
    create_time         TIMESTAMP     NOT NULL,
    update_time         TIMESTAMP     NOT NULL,
    deleted             INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_tool_approval_conv ON mate_tool_approval (conversation_id);
CREATE INDEX IF NOT EXISTS idx_tool_approval_status ON mate_tool_approval (status);
CREATE INDEX IF NOT EXISTS idx_tool_approval_pending_id ON mate_tool_approval (pending_id);

-- 安全规则表
CREATE TABLE IF NOT EXISTS mate_tool_guard_rule (
    id              BIGINT       NOT NULL PRIMARY KEY,
    rule_id         VARCHAR(64)  NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    description     TEXT,
    tool_name       VARCHAR(128),
    param_name      VARCHAR(128),
    category        VARCHAR(64)  NOT NULL,
    severity        VARCHAR(16)  NOT NULL,
    decision        VARCHAR(16)  NOT NULL DEFAULT 'NEEDS_APPROVAL',
    pattern         VARCHAR(512) NOT NULL,
    exclude_pattern VARCHAR(512),
    remediation     TEXT,
    builtin         BOOLEAN   NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN   NOT NULL DEFAULT TRUE,
    priority        INT          NOT NULL DEFAULT 100,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

-- 安全全局配置表
CREATE TABLE IF NOT EXISTS mate_tool_guard_config (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    enabled              BOOLEAN   NOT NULL DEFAULT TRUE,
    guard_scope          VARCHAR(32)  NOT NULL DEFAULT 'all',
    guarded_tools_json   TEXT,
    denied_tools_json    TEXT,
    file_guard_enabled   BOOLEAN   NOT NULL DEFAULT TRUE,
    sensitive_paths_json TEXT,
    audit_enabled        BOOLEAN   NOT NULL DEFAULT TRUE,
    audit_min_severity   VARCHAR(16)  NOT NULL DEFAULT 'INFO',
    audit_retention_days INT          NOT NULL DEFAULT 90,
    create_time          TIMESTAMP     NOT NULL,
    update_time          TIMESTAMP     NOT NULL
);

-- 安全审计日志表
CREATE TABLE IF NOT EXISTS mate_tool_guard_audit_log (
    id                  BIGINT       NOT NULL PRIMARY KEY,
    conversation_id     VARCHAR(128),
    agent_id            VARCHAR(64),
    user_id             VARCHAR(64),
    channel_type        VARCHAR(32),
    tool_name           VARCHAR(128) NOT NULL,
    tool_params_json    TEXT,
    decision            VARCHAR(16)  NOT NULL,
    max_severity        VARCHAR(16),
    findings_json       TEXT,
    pending_id          VARCHAR(32),
    replay_payload_hash VARCHAR(64),
    create_time         TIMESTAMP     NOT NULL,
    update_time         TIMESTAMP     NOT NULL,
    deleted             INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_guard_audit_conv ON mate_tool_guard_audit_log (conversation_id);
CREATE INDEX IF NOT EXISTS idx_guard_audit_time ON mate_tool_guard_audit_log (create_time);

-- ==================== 外部数据源 ====================

CREATE TABLE IF NOT EXISTS mate_datasource (
    id              BIGINT       NOT NULL PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(512),
    db_type         VARCHAR(32)  NOT NULL,
    host            VARCHAR(256) NOT NULL,
    port            INT          NOT NULL,
    database_name   VARCHAR(128) NOT NULL,
    username        VARCHAR(128),
    password        VARCHAR(512),
    extra_params    VARCHAR(512),
    schema_name     VARCHAR(128),
    enabled         BOOLEAN   NOT NULL DEFAULT TRUE,
    last_test_time  TIMESTAMP,
    last_test_ok    BOOLEAN,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

-- ==================== 异步任务（视频/图片生成等长耗时操作） ====================

CREATE TABLE IF NOT EXISTS mate_async_task (
    id               BIGINT        NOT NULL PRIMARY KEY,
    task_id          VARCHAR(64)   NOT NULL,
    task_type        VARCHAR(32)   NOT NULL,
    status           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    conversation_id  VARCHAR(128),
    message_id       BIGINT,
    provider_name    VARCHAR(64),
    provider_task_id VARCHAR(128),
    request_json     TEXT,
    result_json      TEXT,
    error_message    VARCHAR(512),
    progress         INT           DEFAULT 0,
    created_by       VARCHAR(64),
    create_time      TIMESTAMP      NOT NULL,
    update_time      TIMESTAMP      NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_async_task_taskid ON mate_async_task (task_id);
CREATE INDEX IF NOT EXISTS idx_async_task_conv ON mate_async_task (conversation_id);
CREATE INDEX IF NOT EXISTS idx_async_task_status ON mate_async_task (status);

-- ==================== 记忆召回追踪 ====================

CREATE TABLE IF NOT EXISTS mate_memory_recall (
    id                BIGINT       NOT NULL PRIMARY KEY,
    agent_id          BIGINT       NOT NULL,
    filename          VARCHAR(256) NOT NULL,
    snippet_hash      VARCHAR(64),
    snippet_preview   VARCHAR(512),
    recall_count      INT          NOT NULL DEFAULT 0,
    daily_count       INT          NOT NULL DEFAULT 0,
    query_hashes      TEXT,
    score             DOUBLE PRECISION       NOT NULL DEFAULT 0.0,
    last_recalled_at  TIMESTAMP,
    promoted          BOOLEAN   NOT NULL DEFAULT FALSE,
    create_time       TIMESTAMP     NOT NULL,
    update_time       TIMESTAMP     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_memory_recall_agent ON mate_memory_recall (agent_id);
CREATE INDEX IF NOT EXISTS idx_memory_recall_agent_file ON mate_memory_recall (agent_id, filename);
CREATE INDEX IF NOT EXISTS idx_memory_recall_score ON mate_memory_recall (agent_id, score);
CREATE INDEX IF NOT EXISTS idx_memory_recall_candidates ON mate_memory_recall (agent_id, promoted, deleted);

-- ==================== Wiki 知识库 ====================

CREATE TABLE IF NOT EXISTS mate_wiki_knowledge_base (
    id               BIGINT       NOT NULL PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      TEXT,
    agent_id         BIGINT,
    config_content   TEXT,
    source_directory VARCHAR(512),
    status           VARCHAR(32)  NOT NULL DEFAULT 'active',
    page_count       INT          NOT NULL DEFAULT 0,
    raw_count        INT          NOT NULL DEFAULT 0,
    workspace_id     BIGINT       NOT NULL DEFAULT 1,
    create_time      TIMESTAMP     NOT NULL,
    update_time      TIMESTAMP     NOT NULL,
    deleted          INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_kb_agent ON mate_wiki_knowledge_base (agent_id);

CREATE TABLE IF NOT EXISTS mate_wiki_raw_material (
    id                BIGINT       NOT NULL PRIMARY KEY,
    kb_id             BIGINT       NOT NULL,
    title             VARCHAR(256) NOT NULL,
    source_type       VARCHAR(32)  NOT NULL DEFAULT 'text',
    source_path       VARCHAR(512),
    original_content  TEXT,
    extracted_text    TEXT,
    content_hash      VARCHAR(64),
    file_size         BIGINT       NOT NULL DEFAULT 0,
    processing_status VARCHAR(32)  NOT NULL DEFAULT 'pending',
    last_processed_at TIMESTAMP,
    error_message     VARCHAR(512),
    create_time       TIMESTAMP     NOT NULL,
    update_time       TIMESTAMP     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wiki_raw_kb ON mate_wiki_raw_material (kb_id);
CREATE INDEX IF NOT EXISTS idx_wiki_raw_status ON mate_wiki_raw_material (kb_id, processing_status);

CREATE TABLE IF NOT EXISTS mate_wiki_page (
    id              BIGINT       NOT NULL PRIMARY KEY,
    kb_id           BIGINT       NOT NULL,
    slug            VARCHAR(256) NOT NULL,
    title           VARCHAR(256) NOT NULL,
    content         TEXT,
    summary         VARCHAR(1024),
    outgoing_links  TEXT,
    source_raw_ids  TEXT,
    version         INT          NOT NULL DEFAULT 1,
    last_updated_by VARCHAR(32)  NOT NULL DEFAULT 'ai',
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_page_kb_slug ON mate_wiki_page (kb_id, slug);
CREATE INDEX IF NOT EXISTS idx_wiki_page_kb ON mate_wiki_page (kb_id);

-- =============================================
-- 工作区表（Phase 2）
-- =============================================

-- 工作区
CREATE TABLE IF NOT EXISTS mate_workspace (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    description   VARCHAR(256),
    owner_id      BIGINT,
    settings_json TEXT,
    base_path     VARCHAR(512),
    create_time   TIMESTAMP     NOT NULL,
    update_time   TIMESTAMP     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workspace_slug ON mate_workspace (slug);

-- 工作区成员
CREATE TABLE IF NOT EXISTS mate_workspace_member (
    id           BIGINT      NOT NULL PRIMARY KEY,
    workspace_id BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    role         VARCHAR(32) NOT NULL DEFAULT 'member',
    create_time  TIMESTAMP    NOT NULL,
    update_time  TIMESTAMP    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_ws_member_workspace ON mate_workspace_member (workspace_id);
CREATE INDEX IF NOT EXISTS idx_ws_member_user ON mate_workspace_member (user_id);

-- =============================================
-- Agent-Skill / Agent-Tool 绑定表（Phase 3 Sprint 2）
-- =============================================

CREATE TABLE IF NOT EXISTS mate_agent_skill (
    id           BIGINT      NOT NULL PRIMARY KEY,
    agent_id     BIGINT      NOT NULL,
    skill_id     BIGINT      NOT NULL,
    enabled      BOOLEAN  NOT NULL DEFAULT TRUE,
    config_json  TEXT,
    create_time  TIMESTAMP    NOT NULL,
    update_time  TIMESTAMP    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_skill ON mate_agent_skill (agent_id, skill_id);

CREATE TABLE IF NOT EXISTS mate_agent_tool (
    id           BIGINT      NOT NULL PRIMARY KEY,
    agent_id     BIGINT      NOT NULL,
    tool_name    VARCHAR(128) NOT NULL,
    enabled      BOOLEAN  NOT NULL DEFAULT TRUE,
    create_time  TIMESTAMP    NOT NULL,
    update_time  TIMESTAMP    NOT NULL,
    deleted      INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_tool ON mate_agent_tool (agent_id, tool_name);

-- =============================================
-- CronJob 执行历史（Phase 3 Sprint 3）
-- =============================================
CREATE TABLE IF NOT EXISTS mate_cron_job_run (
    id              BIGINT       NOT NULL PRIMARY KEY,
    cron_job_id     BIGINT       NOT NULL,
    conversation_id VARCHAR(64),
    status          VARCHAR(32)  NOT NULL,
    trigger_type    VARCHAR(32)  NOT NULL DEFAULT 'scheduled',
    started_at      TIMESTAMP     NOT NULL,
    finished_at     TIMESTAMP,
    error_message   TEXT,
    token_usage     INT          DEFAULT 0,
    create_time     TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cron_run_job ON mate_cron_job_run (cron_job_id, started_at);

CREATE TABLE IF NOT EXISTS mate_usage_daily (
    id                 BIGINT   NOT NULL PRIMARY KEY,
    workspace_id       BIGINT   NOT NULL,
    agent_id           BIGINT,
    stat_date          DATE     NOT NULL,
    conversation_count INT      DEFAULT 0,
    message_count      INT      DEFAULT 0,
    total_tokens       BIGINT   DEFAULT 0,
    prompt_tokens      BIGINT   DEFAULT 0,
    completion_tokens  BIGINT   DEFAULT 0,
    tool_call_count    INT      DEFAULT 0,
    error_count        INT      DEFAULT 0,
    create_time        TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_daily ON mate_usage_daily (workspace_id, agent_id, stat_date);

-- =============================================
-- 操作审计事件表（Phase 3 Sprint 1）
-- =============================================
CREATE TABLE IF NOT EXISTS mate_audit_event (
    id             BIGINT       NOT NULL PRIMARY KEY,
    workspace_id   BIGINT,
    user_id        BIGINT       NOT NULL,
    username       VARCHAR(64)  NOT NULL,
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(64)  NOT NULL,
    resource_id    VARCHAR(128),
    resource_name  VARCHAR(256),
    detail_json    TEXT,
    ip_address     VARCHAR(64),
    user_agent     VARCHAR(256),
    create_time    TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_ws_time ON mate_audit_event (workspace_id, create_time);
CREATE INDEX IF NOT EXISTS idx_audit_user ON mate_audit_event (user_id);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON mate_audit_event (resource_type, resource_id);

-- 清理 Codex 不支持的 ChatGPT OAuth 模型（gpt-4o, o3, o4-mini 在 Codex 模式下不可用）
DELETE FROM mate_model_config WHERE provider = 'openai-chatgpt' AND model_name IN ('gpt-4o', 'o3', 'o4-mini');
