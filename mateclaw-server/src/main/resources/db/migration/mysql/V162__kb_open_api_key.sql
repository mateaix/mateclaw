-- V161: Knowledge Base Open API Key.
-- External API keys for programmatic access to knowledge base retrieval,
-- tracing, and graph traversal. Like PAT (V76), plaintext is never stored —
-- only the SHA-256 hash. A DB compromise reveals ownership, scope, and bound
-- KBs but never the secret needed to authenticate.
--
-- One key can bind multiple KBs (mate_kb_api_key_binding). An empty binding
-- set means "zero access" (NOT "all KBs" — unlike internal AgentWikiKbBinding).

CREATE TABLE IF NOT EXISTS mate_kb_api_key (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    name               VARCHAR(128) NOT NULL,
    token_hash         CHAR(64)     NOT NULL,
    prefix             VARCHAR(6)   NOT NULL,
    workspace_id       BIGINT       NOT NULL,
    created_by         BIGINT       NOT NULL,
    scopes             VARCHAR(255),
    enabled            BOOLEAN      DEFAULT TRUE,
    expires_at         TIMESTAMP    NULL,
    last_used_at       TIMESTAMP    NULL,
    rate_limit_per_min INT          NOT NULL DEFAULT 60,
    create_time        TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted            INT          DEFAULT 0,
    UNIQUE KEY uk_kb_api_key_hash (token_hash),
    KEY idx_kb_api_key_workspace (workspace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_kb_api_key_binding (
    id           BIGINT    NOT NULL PRIMARY KEY,
    api_key_id   BIGINT    NOT NULL,
    kb_id        BIGINT    NOT NULL,
    create_time  TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_kb_api_key_binding (api_key_id, kb_id),
    KEY idx_kb_api_key_binding_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
