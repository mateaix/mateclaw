-- mate_wiki_entity_mention: links a canonical entity to a source occurrence.
-- See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_entity_mention (
    id            BIGINT PRIMARY KEY,
    kb_id         BIGINT NOT NULL,
    entity_id     BIGINT NOT NULL,
    chunk_id      BIGINT,
    page_id       BIGINT,

    surface_form  VARCHAR(256),
    char_offset   INT,
    confidence    DECIMAL(4, 3),
    evidence      TEXT,
    source        VARCHAR(32),

    create_time   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT     NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wem_entity ON mate_wiki_entity_mention (entity_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wem_chunk  ON mate_wiki_entity_mention (chunk_id);
CREATE INDEX IF NOT EXISTS idx_wem_page   ON mate_wiki_entity_mention (page_id);
CREATE INDEX IF NOT EXISTS idx_wem_kb     ON mate_wiki_entity_mention (kb_id, deleted);
