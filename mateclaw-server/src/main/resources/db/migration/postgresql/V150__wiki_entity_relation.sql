-- mate_wiki_entity_relation: directed subject -> predicate -> object triples
-- between canonical entities. See the H2 file for the design rationale.

CREATE TABLE IF NOT EXISTS mate_wiki_entity_relation (
    id                BIGINT PRIMARY KEY,
    kb_id             BIGINT NOT NULL,

    subject_entity_id BIGINT NOT NULL,
    predicate         VARCHAR(64) NOT NULL,
    object_entity_id  BIGINT NOT NULL,

    evidence          TEXT,
    confidence        DECIMAL(4, 3),
    source            VARCHAR(32),
    evidence_chunk_id BIGINT,
    computed_hash     VARCHAR(64),

    create_time       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted           SMALLINT     NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_wer_triple
    ON mate_wiki_entity_relation (kb_id, subject_entity_id, predicate, object_entity_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wer_subject ON mate_wiki_entity_relation (kb_id, subject_entity_id);
CREATE INDEX IF NOT EXISTS idx_wer_object  ON mate_wiki_entity_relation (kb_id, object_entity_id);
CREATE INDEX IF NOT EXISTS idx_wer_kb      ON mate_wiki_entity_relation (kb_id, deleted);
