-- mate_wiki_relation: persistent cache of page-to-page multi-signal relations.
--
-- Distinct from mate_wiki_page_citation, which models page-to-chunk citations.
-- A row here represents one directed (or undirected, see notes below) edge in
-- the wiki page graph for a given knowledge base, materializing:
--   * the aggregate relevance score across registered signal strategies
--     (direct link, shared chunk, shared raw, semantic similarity, ...)
--   * a per-signal breakdown for explainability
--   * an optional taxonomy tag (mention / cite / supports / contradicts /
--     extends) populated by the planning stage of the compile pipeline
--   * confidence + evidence snippets sourced from the same compile output
--   * cache invalidation metadata so readers can decide whether to recompute

CREATE TABLE IF NOT EXISTS mate_wiki_relation (
    id              BIGINT  PRIMARY KEY,
    kb_id           BIGINT NOT NULL,
    page_a_id       BIGINT NOT NULL,
    page_b_id       BIGINT NOT NULL,

    total_score     DECIMAL(8, 4),
    signals_json    TEXT,

    type            VARCHAR(32),

    confidence      VARCHAR(16),
    evidence        TEXT,
    evidence_raw_id BIGINT,

    source          VARCHAR(32),

    computed_at     TIMESTAMP(3),
    computed_hash   VARCHAR(64),

    create_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted         SMALLINT     NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wr_pair ON mate_wiki_relation (kb_id, page_a_id, page_b_id, deleted);
CREATE INDEX IF NOT EXISTS idx_wr_page_a ON mate_wiki_relation (kb_id, page_a_id, total_score DESC);
CREATE INDEX IF NOT EXISTS idx_wr_kb_score ON mate_wiki_relation (kb_id, total_score DESC);
CREATE INDEX IF NOT EXISTS idx_wr_computed_at ON mate_wiki_relation (computed_at);
