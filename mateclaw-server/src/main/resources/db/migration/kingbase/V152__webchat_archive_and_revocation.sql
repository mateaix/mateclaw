-- V148: webchat visitor-session archive flag + visitor-token revocation registry (KingbaseES / PostgreSQL).
-- See the H2 copy for full context.

ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS archived INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS webchat_revoked_visitor (
    id          BIGINT       NOT NULL PRIMARY KEY,
    channel_id  BIGINT       NOT NULL,
    visitor_id  VARCHAR(128) NOT NULL,
    revoked_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason      VARCHAR(255),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_webchat_revoked_visitor
    ON webchat_revoked_visitor (channel_id, visitor_id, deleted);
CREATE INDEX IF NOT EXISTS idx_webchat_revoked_visitor_lookup
    ON webchat_revoked_visitor (channel_id, visitor_id, deleted);
