-- mate_wiki_entity_mention: links a canonical entity to a source occurrence.
--
-- One row per (entity, chunk) occurrence. page_id is back-filled from the
-- chunk's citing pages so the entity layer connects to the page layer:
-- entity -> mention -> chunk -> citing page. A NULL page_id means the source
-- chunk is not yet cited by any generated page.
--
--   entity_id     the resolved canonical entity (mate_wiki_entity.id)
--   chunk_id      source chunk the mention was found in
--   page_id       a wiki page that cites that chunk, when known
--   surface_form  the exact text as it appeared in the source
--   char_offset   character offset of the mention within the chunk, when known
--   confidence    0..1 extraction confidence
--   evidence      short surrounding quote (<= 500 chars enforced in Java)
--   source        provenance tag: llm-extracted | manual

CREATE TABLE IF NOT EXISTS mate_wiki_entity_mention (
    id            BIGINT PRIMARY KEY,
    kb_id         BIGINT NOT NULL,
    entity_id     BIGINT NOT NULL,
    chunk_id      BIGINT,
    page_id       BIGINT,

    surface_form  VARCHAR(256),
    char_offset   INT,
    confidence    DECIMAL(4, 3),
    evidence      CLOB,
    source        VARCHAR(32),

    create_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT       NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_wem_entity ON mate_wiki_entity_mention (entity_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wem_chunk  ON mate_wiki_entity_mention (chunk_id);
CREATE INDEX IF NOT EXISTS idx_wem_page   ON mate_wiki_entity_mention (page_id);
CREATE INDEX IF NOT EXISTS idx_wem_kb     ON mate_wiki_entity_mention (kb_id, deleted);
