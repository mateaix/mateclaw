-- Dream v2 Phase 3: Fact projection tables (read-only derived from canonical)
-- Ref: rfc-038 §3.3

CREATE TABLE IF NOT EXISTS mate_fact (
    id              BIGINT PRIMARY KEY ,
    agent_id        BIGINT NOT NULL,
    source_ref      VARCHAR(512) NOT NULL,
    category        VARCHAR(64),
    subject         VARCHAR(256),
    predicate       VARCHAR(256),
    object_value    TEXT,
    confidence      DOUBLE PRECISION DEFAULT 1.0,
    trust           DOUBLE PRECISION DEFAULT 0.5,
    last_used_at    TIMESTAMP,
    use_count       INT DEFAULT 0,
    extracted_by    VARCHAR(32) DEFAULT 'pattern',
    create_time     TIMESTAMP NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    deleted         SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_fact_agent_source ON mate_fact (agent_id, source_ref);
CREATE INDEX IF NOT EXISTS idx_fact_agent_subject ON mate_fact (agent_id, subject);
CREATE INDEX IF NOT EXISTS idx_fact_agent ON mate_fact (agent_id, deleted);

CREATE TABLE IF NOT EXISTS mate_fact_entity_ref (
    id              BIGINT PRIMARY KEY ,
    fact_id         BIGINT NOT NULL,
    entity_name     VARCHAR(256) NOT NULL,
    entity_type     VARCHAR(64),
    role            VARCHAR(32) NOT NULL,
    create_time     TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_fact_ref_entity ON mate_fact_entity_ref (entity_name, entity_type);
CREATE INDEX IF NOT EXISTS idx_fact_ref_fact ON mate_fact_entity_ref (fact_id);

CREATE TABLE IF NOT EXISTS mate_fact_contradiction (
    id              BIGINT PRIMARY KEY ,
    agent_id        BIGINT NOT NULL,
    fact_a_id       BIGINT NOT NULL,
    fact_b_id       BIGINT NOT NULL,
    description     TEXT,
    resolution      VARCHAR(32),
    resolved_at     TIMESTAMP,
    resolved_by     VARCHAR(64),
    create_time     TIMESTAMP NOT NULL,
    update_time     TIMESTAMP NOT NULL,
    deleted         SMALLINT DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_contradiction_agent ON mate_fact_contradiction (agent_id, resolution);
