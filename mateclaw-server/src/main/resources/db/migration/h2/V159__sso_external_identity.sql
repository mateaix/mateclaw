-- V159: SSO (single sign-on) infrastructure — external identity link table,
-- OAuth2 state store, and mate_user.password relaxation.
--
-- This migration introduces three changes for ISSUE #405 (飞书/SSO login):
--
--   1. mate_user_external_identity — links a mate_user to one or more IdP
--      identities (feishu open_id / union_id, later dingtalk, wecom, ...).
--      A single user may bind multiple providers; a single (provider,
--      external_id) pair maps to at most one user.
--
--      Matching priority at login: union_id first (cross-app unique within a
--      Feishu tenant — requires the app to request the union_id data scope),
--      falling back to external_id (open_id, app-local). Deployments that do
--      not enable the union_id scope cannot de-duplicate across apps; the
--      deployment note must call this out.
--
--      Soft-delete (@TableLogic, matching wiki-package convention): unbinding
--      rewrites external_id/union_id to `<orig>_del_<epochMillis>` so the
--      UNIQUE constraints free up for a future re-bind, while the old row is
--      retained for audit. mate_user itself is NOT soft-delete in this repo
--      (V20 purged soft-delete on non-wiki tables), so the two tables have
--      independent delete semantics — only this table is @TableLogic.
--
--   2. sso_state — persists OAuth2 'state' tokens and bind_token 'jti' values
--      so they are one-time-consumable across a multi-node deployment (in-mem
--      would lose state between /authorize on node A and /callback on node B).
--      The 'kind' column distinguishes 'state' (OAuth2 CSRF state) from 'bind'
--      (bind_token anti-replay jti). A ShedLock hourly job purges expired rows
--      (5-min state TTL + buffer) — the purge uses LambdaQuery + Java time so
--      it works on all three dialects without a NOW()-INTERVAL literal.
--
--   3. ALTER mate_user.password — relax from NOT NULL to nullable so an
--      SSO-only user (never set a local password) can exist. AuthService.login
--      guards password IS NULL → reject password login (BCrypt null-hash is a
--      false match anyway, but an explicit check is clearer and audit-safe).

-- (1) external identity link ------------------------------------------------

CREATE TABLE IF NOT EXISTS mate_user_external_identity (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,

    provider        VARCHAR(32)  NOT NULL,   -- feishu / dingtalk / wecom / ...
    external_id     VARCHAR(128) NOT NULL,   -- IdP-scoped id, usually open_id
    union_id        VARCHAR(128),            -- cross-app unique (Feishu), nullable
    external_name   VARCHAR(128),            -- IdP-side display name
    external_avatar VARCHAR(512),
    external_email  VARCHAR(128),

    last_login_at   TIMESTAMP,
    create_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0
);

-- A single IdP identity maps to at most one active user.
CREATE UNIQUE INDEX IF NOT EXISTS uk_sso_provider_external
    ON mate_user_external_identity (provider, external_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sso_provider_union
    ON mate_user_external_identity (provider, union_id);
-- List a user's bound identities.
CREATE INDEX IF NOT EXISTS idx_sso_user_provider
    ON mate_user_external_identity (user_id, provider);

-- (2) OAuth2 state / bind-token store ---------------------------------------

CREATE TABLE IF NOT EXISTS sso_state (
    token       VARCHAR(128) PRIMARY KEY,    -- state value or bind-token jti
    kind        VARCHAR(8)  NOT NULL,        -- 'state' | 'bind'
    provider    VARCHAR(32),                 -- provider id (null for 'bind' jti reuse)
    consumed    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- (3) relax mate_user.password to nullable ----------------------------------
--     SSO-only users never set a local password; AuthService.login rejects
--     password=null before the BCrypt check.

ALTER TABLE mate_user ALTER COLUMN password DROP NOT NULL;
