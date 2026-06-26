-- V2: Upgrade schema for databases created before Flyway was introduced.
-- MySQL does NOT support ALTER TABLE ... ADD COLUMN IF NOT EXISTS (MariaDB-only).
-- We use INFORMATION_SCHEMA + dynamic SQL as an idempotent replacement so this migration
-- is safe on BOTH: (a) fresh MySQL installs whose V1 baseline already contains the columns,
-- and (b) legacy installs bootstrapped from the old schema.sql that predates those columns.

CREATE TABLE IF NOT EXISTS mate_workspace (
    id            BIGINT       NOT NULL PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    description   VARCHAR(256),
    owner_id      BIGINT,
    settings_json JSONB,
    base_path     VARCHAR(512),
    create_time   TIMESTAMP     NOT NULL,
    update_time   TIMESTAMP     NOT NULL,
    deleted       INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_workspace_slug ON mate_workspace (slug);

-- mate_workspace.base_path (legacy upgrade path)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_workspace' AND column_name = 'base_path'
    ) THEN
        ALTER TABLE mate_workspace ADD COLUMN base_path VARCHAR(512);
    END IF;
END $$;

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

-- workspace_id on pre-existing domain tables
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_agent' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_agent ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_channel' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_channel ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_conversation' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_conversation ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_knowledge_base' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_wiki_knowledge_base ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_tool' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_tool ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_skill' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_skill ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS mate_workspace_file (
    id              BIGINT       NOT NULL PRIMARY KEY,
    agent_id        BIGINT       NOT NULL,
    filename        VARCHAR(256) NOT NULL,
    content         TEXT,
    file_size       BIGINT       NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    sort_order      INT          NOT NULL DEFAULT 0,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_workspace_file_agent ON mate_workspace_file (agent_id);

CREATE TABLE IF NOT EXISTS mate_usage_daily (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL,
    agent_id           BIGINT       NOT NULL,
    stat_date          DATE         NOT NULL,
    conversation_count INT          NOT NULL DEFAULT 0,
    message_count      INT          NOT NULL DEFAULT 0,
    tool_call_count    INT          NOT NULL DEFAULT 0,
    prompt_tokens      BIGINT       NOT NULL DEFAULT 0,
    completion_tokens  BIGINT       NOT NULL DEFAULT 0,
    create_time        TIMESTAMP     NOT NULL,
    update_time        TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_usage_daily ON mate_usage_daily (workspace_id, agent_id, stat_date);

CREATE TABLE IF NOT EXISTS mate_audit_event (
    id             BIGINT       NOT NULL PRIMARY KEY,
    workspace_id   BIGINT,
    user_id        BIGINT,
    username       VARCHAR(64),
    action         VARCHAR(64)  NOT NULL,
    resource_type  VARCHAR(64),
    resource_id    VARCHAR(128),
    detail         TEXT,
    ip_address     VARCHAR(64),
    create_time    TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_ws_time ON mate_audit_event (workspace_id, create_time);

-- model provider OAuth columns
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'auth_type'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN auth_type VARCHAR(16) NOT NULL DEFAULT 'api_key';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'oauth_access_token'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN oauth_access_token TEXT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'oauth_refresh_token'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN oauth_refresh_token TEXT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'oauth_expires_at'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN oauth_expires_at BIGINT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'oauth_account_id'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN oauth_account_id VARCHAR(128);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS mate_agent_skill (
    id           BIGINT    NOT NULL PRIMARY KEY,
    agent_id     BIGINT    NOT NULL,
    skill_id     BIGINT    NOT NULL,
    create_time  TIMESTAMP  NOT NULL,
    deleted      INT       NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS mate_agent_tool (
    id           BIGINT    NOT NULL PRIMARY KEY,
    agent_id     BIGINT    NOT NULL,
    tool_name    VARCHAR(128) NOT NULL,
    create_time  TIMESTAMP  NOT NULL,
    deleted      INT       NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS mate_memory_recall (
    id                BIGINT       NOT NULL PRIMARY KEY,
    agent_id          BIGINT       NOT NULL,
    filename          VARCHAR(256) NOT NULL,
    content           TEXT,
    tags              VARCHAR(512),
    score             DOUBLE PRECISION       NOT NULL DEFAULT 0.0,
    last_recalled_at  TIMESTAMP,
    promoted          BOOLEAN      NOT NULL DEFAULT FALSE,
    create_time       TIMESTAMP     NOT NULL,
    update_time       TIMESTAMP     NOT NULL,
    deleted           INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_memory_recall_agent ON mate_memory_recall (agent_id);
