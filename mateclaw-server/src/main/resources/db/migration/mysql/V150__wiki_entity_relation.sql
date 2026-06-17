-- mate_wiki_entity_relation: directed subject -> predicate -> object triples
-- between canonical entities. See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_entity_relation (
    id                BIGINT NOT NULL,
    kb_id             BIGINT NOT NULL,

    subject_entity_id BIGINT NOT NULL,
    predicate         VARCHAR(64) NOT NULL,
    object_entity_id  BIGINT NOT NULL,

    evidence          TEXT,
    confidence        DECIMAL(4, 3),
    source            VARCHAR(32),
    evidence_chunk_id BIGINT,
    computed_hash     VARCHAR(64),

    create_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted           TINYINT     NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_wer_triple (kb_id, subject_entity_id, predicate, object_entity_id, deleted),
    KEY        idx_wer_subject (kb_id, subject_entity_id),
    KEY        idx_wer_object  (kb_id, object_entity_id),
    KEY        idx_wer_kb      (kb_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Entity-to-entity fact triples forming the entity-level knowledge graph edges.';
