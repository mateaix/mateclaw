-- mate_wiki_entity: canonical named-entity nodes extracted from source chunks.
-- See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_entity (
    id              BIGINT NOT NULL,
    kb_id           BIGINT NOT NULL,

    canonical_name  VARCHAR(256) NOT NULL,
    normalized_key  VARCHAR(256) NOT NULL,
    type            VARCHAR(32)  NOT NULL,

    aliases_json    LONGTEXT,
    description     LONGTEXT,
    salience        DECIMAL(5, 4),
    mention_count   INT NOT NULL DEFAULT 0,

    embedding       BLOB,
    embedding_model VARCHAR(64),

    computed_hash   VARCHAR(64),

    create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted         TINYINT     NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_we_key      (kb_id, normalized_key, type, deleted),
    KEY        idx_we_kb       (kb_id, deleted),
    KEY        idx_we_salience (kb_id, salience DESC),
    KEY        idx_we_type     (kb_id, type, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Canonical named-entity nodes extracted and de-duplicated from source chunks.';
