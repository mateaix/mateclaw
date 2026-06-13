-- V129: Persisted wikilink lint state — KingbaseES dialect.
--
-- See h2/V129__wiki_page_broken_links.sql for column semantics.
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS broken_links JSONB DEFAULT NULL;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS broken_links_scanned_at TIMESTAMP(3) DEFAULT NULL;
