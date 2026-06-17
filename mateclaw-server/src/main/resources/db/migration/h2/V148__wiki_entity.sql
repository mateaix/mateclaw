-- mate_wiki_entity: canonical named-entity nodes extracted from source chunks.
--
-- Distinct from mate_wiki_page (document/topic granularity) — a row here is a
-- mention-granularity named entity (person, organization, location, event, ...)
-- resolved and de-duplicated across the knowledge base. The entity layer sits
-- beneath the page layer: entities are linked to their source chunks (and, via
-- citing pages, to wiki pages) through mate_wiki_entity_mention, and to each
-- other through mate_wiki_entity_relation.
--
--   canonical_name  display name chosen for the merged entity
--   normalized_key  case/whitespace-folded key used for exact-match dedup
--   type            entity taxonomy: person | organization | location |
--                   event | product | concept | other
--   aliases_json    JSON array of surface forms merged into this entity
--   description     one-line summary synthesized from the mentions
--   salience        0..1 importance score (mention frequency / distribution)
--   mention_count   number of mentions resolved to this entity
--   embedding       float32 little-endian name/description vector used for
--                   near-duplicate merge across spellings/languages
--   computed_hash   fingerprint of the inputs that produced this row

CREATE TABLE IF NOT EXISTS mate_wiki_entity (
    id              BIGINT PRIMARY KEY,
    kb_id           BIGINT NOT NULL,

    canonical_name  VARCHAR(256) NOT NULL,
    normalized_key  VARCHAR(256) NOT NULL,
    type            VARCHAR(32)  NOT NULL,

    aliases_json    CLOB,
    description     CLOB,
    salience        DECIMAL(5, 4),
    mention_count   INT NOT NULL DEFAULT 0,

    embedding       BLOB,
    embedding_model VARCHAR(64),

    computed_hash   VARCHAR(64),

    create_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT       NOT NULL DEFAULT 0
);

-- Exact-match dedup identity within a KB (deleted included so soft-deleted
-- rows can coexist with re-inserted ones during re-extract cycles).
CREATE UNIQUE INDEX IF NOT EXISTS uk_we_key
    ON mate_wiki_entity (kb_id, normalized_key, type, deleted);

-- KB-wide listing and "top entities by salience".
CREATE INDEX IF NOT EXISTS idx_we_kb
    ON mate_wiki_entity (kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_we_salience
    ON mate_wiki_entity (kb_id, salience DESC);
CREATE INDEX IF NOT EXISTS idx_we_type
    ON mate_wiki_entity (kb_id, type, deleted);
