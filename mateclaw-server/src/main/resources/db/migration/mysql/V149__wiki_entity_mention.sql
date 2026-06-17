-- mate_wiki_entity_mention: links a canonical entity to a source occurrence.
-- See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_entity_mention (
    id            BIGINT NOT NULL,
    kb_id         BIGINT NOT NULL,
    entity_id     BIGINT NOT NULL,
    chunk_id      BIGINT,
    page_id       BIGINT,

    surface_form  VARCHAR(256),
    char_offset   INT,
    confidence    DECIMAL(4, 3),
    evidence      TEXT,
    source        VARCHAR(32),

    create_time   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted       TINYINT     NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    KEY idx_wem_entity (entity_id, deleted),
    KEY idx_wem_chunk  (chunk_id),
    KEY idx_wem_page   (page_id),
    KEY idx_wem_kb     (kb_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Entity-to-source occurrence links connecting the entity layer to chunks and pages.';
