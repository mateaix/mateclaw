-- V159: SSO (single sign-on) infrastructure — external identity link table,
-- OAuth2 state store, and mate_user.password relaxation.
-- See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_user_external_identity (
    id              BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,

    provider        VARCHAR(32)  NOT NULL,
    external_id     VARCHAR(128) NOT NULL,
    union_id        VARCHAR(128),
    external_name   VARCHAR(128),
    external_avatar VARCHAR(512),
    external_email  VARCHAR(128),

    last_login_at   DATETIME(3),
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT      NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_sso_provider_external (provider, external_id),
    UNIQUE KEY uk_sso_provider_union    (provider, union_id),
    KEY        idx_sso_user_provider    (user_id, provider)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'User external identity links for SSO (feishu/dingtalk/...).';

CREATE TABLE IF NOT EXISTS sso_state (
    token       VARCHAR(128) NOT NULL,
    kind        VARCHAR(8)   NOT NULL,
    provider    VARCHAR(32),
    consumed    TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (token)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'OAuth2 state + bind-token jti store (one-time consumable, multi-node).';

ALTER TABLE mate_user MODIFY COLUMN password VARCHAR(200) NULL;
