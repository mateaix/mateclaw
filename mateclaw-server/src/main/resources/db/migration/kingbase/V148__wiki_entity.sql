-- mate_wiki_entity: canonical named-entity nodes extracted from source chunks.
-- See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_entity (
    id              BIGINT PRIMARY KEY,
    kb_id           BIGINT NOT NULL,

    canonical_name  VARCHAR(256) NOT NULL,
    normalized_key  VARCHAR(256) NOT NULL,
    type            VARCHAR(32)  NOT NULL,

    aliases_json    TEXT,
    description     TEXT,
    salience        DECIMAL(5, 4),
    mention_count   INT NOT NULL DEFAULT 0,

    embedding       BYTEA,
    embedding_model VARCHAR(64),

    computed_hash   VARCHAR(64),

    create_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_we_key
    ON mate_wiki_entity (kb_id, normalized_key, type, deleted);
CREATE INDEX IF NOT EXISTS idx_we_kb       ON mate_wiki_entity (kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_we_salience ON mate_wiki_entity (kb_id, salience DESC);
CREATE INDEX IF NOT EXISTS idx_we_type     ON mate_wiki_entity (kb_id, type, deleted);
