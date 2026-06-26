-- V159: SSO (single sign-on) infrastructure — external identity link table,
-- OAuth2 state store, and mate_user.password relaxation.
-- See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_user_external_identity (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,

    provider        VARCHAR(32)  NOT NULL,
    external_id     VARCHAR(128) NOT NULL,
    union_id        VARCHAR(128),
    external_name   VARCHAR(128),
    external_avatar VARCHAR(512),
    external_email  VARCHAR(128),

    last_login_at   TIMESTAMP(3),
    create_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sso_provider_external
    ON mate_user_external_identity (provider, external_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sso_provider_union
    ON mate_user_external_identity (provider, union_id);
CREATE INDEX IF NOT EXISTS idx_sso_user_provider
    ON mate_user_external_identity (user_id, provider);

CREATE TABLE IF NOT EXISTS sso_state (
    token       VARCHAR(128) PRIMARY KEY,
    kind        VARCHAR(8)   NOT NULL,
    provider    VARCHAR(32),
    consumed    SMALLINT     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE mate_user ALTER COLUMN password DROP NOT NULL;
