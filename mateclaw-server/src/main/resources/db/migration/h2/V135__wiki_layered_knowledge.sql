-- V135: Layered knowledge (fact / experience) + page dependency graph.
--
-- knowledge_layer is derived from the pageType profile (fact = "what is",
-- experience = "what it means"). Experience pages depend on fact pages; the
-- dependency table is the source of truth for stale propagation (reverse
-- lookup by depends_on_page_id), with the page-local depends_on_json kept as
-- a redundant copy. Stored by page id, never slug, so renames cannot break a
-- dependency.

ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS knowledge_layer VARCHAR(16);
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS depends_on_json CLOB;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS stale TINYINT NOT NULL DEFAULT 0;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS stale_reason_json CLOB;

CREATE TABLE IF NOT EXISTS mate_wiki_page_dependency (
    id                  BIGINT      NOT NULL PRIMARY KEY,
    kb_id               BIGINT      NOT NULL,
    page_id             BIGINT      NOT NULL,
    depends_on_page_id  BIGINT      NOT NULL,
    dependency_type     VARCHAR(32) NOT NULL DEFAULT 'fact',
    create_time         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_page_dep
    ON mate_wiki_page_dependency (page_id, depends_on_page_id, dependency_type, deleted);
-- Reverse lookup for stale propagation: "who depends on this fact page".
CREATE INDEX IF NOT EXISTS idx_wiki_page_dep_reverse
    ON mate_wiki_page_dependency (kb_id, depends_on_page_id, deleted);
