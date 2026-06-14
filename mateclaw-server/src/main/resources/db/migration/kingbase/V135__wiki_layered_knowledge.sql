-- V135: Layered knowledge (fact / experience) + page dependency graph.
-- See the H2 file for rationale. MySQL uses INFORMATION_SCHEMA guards for the
-- idempotent column adds.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'knowledge_layer'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN knowledge_layer VARCHAR(16);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'depends_on_json'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN depends_on_json TEXT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'stale'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN stale BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'stale_reason_json'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN stale_reason_json TEXT;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS mate_wiki_page_dependency (
    id                  BIGINT      NOT NULL PRIMARY KEY,
    kb_id               BIGINT      NOT NULL,
    page_id             BIGINT      NOT NULL,
    depends_on_page_id  BIGINT      NOT NULL,
    dependency_type     VARCHAR(32) NOT NULL DEFAULT 'fact',
    create_time         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             INT         NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_page_dep ON mate_wiki_page_dependency (page_id, depends_on_page_id, dependency_type, deleted);
CREATE INDEX IF NOT EXISTS idx_wiki_page_dep_reverse ON mate_wiki_page_dependency (kb_id, depends_on_page_id, deleted);
