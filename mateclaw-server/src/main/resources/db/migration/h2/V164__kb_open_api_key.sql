-- V164: Knowledge Base Open API Key (H2 dialect).
-- See mysql/V164 for full design notes.

CREATE TABLE IF NOT EXISTS mate_kb_api_key (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    token_hash         CHAR(64)     NOT NULL,
    prefix             VARCHAR(12)  NOT NULL,
    workspace_id       BIGINT       NOT NULL,
    created_by         BIGINT       NOT NULL,
    scopes             VARCHAR(255),
    enabled            BOOLEAN      DEFAULT TRUE,
    expires_at         TIMESTAMP    NULL,
    last_used_at       TIMESTAMP    NULL,
    rate_limit_per_min INT          NOT NULL DEFAULT 60,
    create_time        TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            INT          DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_api_key_hash ON mate_kb_api_key(token_hash);
CREATE INDEX IF NOT EXISTS idx_kb_api_key_workspace ON mate_kb_api_key(workspace_id);

CREATE TABLE IF NOT EXISTS mate_kb_api_key_binding (
    id           BIGINT    NOT NULL PRIMARY KEY,
    api_key_id   BIGINT    NOT NULL,
    kb_id        BIGINT    NOT NULL,
    create_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_kb_api_key_binding ON mate_kb_api_key_binding(api_key_id, kb_id);
CREATE INDEX IF NOT EXISTS idx_kb_api_key_binding_kb ON mate_kb_api_key_binding(kb_id);
