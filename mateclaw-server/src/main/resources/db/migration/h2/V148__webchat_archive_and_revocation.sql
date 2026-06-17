-- V148: webchat visitor-session archive flag + visitor-token revocation registry.
--
-- 1) mate_conversation.archived — INT, 0 (default) = active, 1 = archived.
--    Lets a visitor "soft-close" a thread: it stays in the DB (history
--    preserved, downloadable, addressable by sessionId) but is excluded
--    from the default /sessions listing. Pinned/archived are orthogonal:
--    archive dominates (an archived+pinned thread is still hidden by default).
--
-- 2) webchat_revoked_visitor — registry of visitors whose visitorToken HMAC
--    is no longer accepted on management endpoints (list/messages/title/
--    delete/stop/upload/regenerate). /stream is intentionally NOT bound by
--    this: a revoked visitor can still start a fresh /stream, which mints
--    a new token; the revocation applies to the old token presented on
--    management endpoints. The (channel_id, visitor_id) pair is unique among
--    non-deleted rows so a re-revoke is idempotent; setting deleted=1
--    un-revokes.

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
