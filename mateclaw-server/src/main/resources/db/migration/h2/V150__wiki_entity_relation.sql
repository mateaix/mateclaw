-- mate_wiki_entity_relation: directed subject -> predicate -> object triples
-- between canonical entities (the entity-level knowledge graph edges).
--
-- Distinct from mate_wiki_relation, which scores page-to-page edges. A row
-- here is one fact triple connecting two mate_wiki_entity nodes.
--
--   subject_entity_id  head entity
--   predicate          free-text relation label (e.g. "works_for", "located_in")
--   object_entity_id   tail entity
--   evidence           short justification quote (<= 500 chars enforced in Java)
--   confidence         0..1 extraction confidence
--   source             provenance tag: llm-extracted | inferred | manual
--   evidence_chunk_id  source chunk the triple was extracted from, when known
--   computed_hash      fingerprint of the inputs that produced this row

CREATE TABLE IF NOT EXISTS mate_wiki_entity_relation (
    id                BIGINT PRIMARY KEY,
    kb_id             BIGINT NOT NULL,

    subject_entity_id BIGINT NOT NULL,
    predicate         VARCHAR(64) NOT NULL,
    object_entity_id  BIGINT NOT NULL,

    evidence          CLOB,
    confidence        DECIMAL(4, 3),
    source            VARCHAR(32),
    evidence_chunk_id BIGINT,
    computed_hash     VARCHAR(64),

    create_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           INT       NOT NULL DEFAULT 0
);

-- Unique triple identity within a KB.
CREATE UNIQUE INDEX IF NOT EXISTS uk_wer_triple
    ON mate_wiki_entity_relation (kb_id, subject_entity_id, predicate, object_entity_id, deleted);

-- Ego-graph traversal in both directions.
CREATE INDEX IF NOT EXISTS idx_wer_subject ON mate_wiki_entity_relation (kb_id, subject_entity_id);
CREATE INDEX IF NOT EXISTS idx_wer_object  ON mate_wiki_entity_relation (kb_id, object_entity_id);
CREATE INDEX IF NOT EXISTS idx_wer_kb      ON mate_wiki_entity_relation (kb_id, deleted);
