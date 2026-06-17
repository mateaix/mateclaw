-- V147: Page aliases — KingbaseES dialect.
--
-- See h2/V147__wiki_page_aliases.sql for column semantics.
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS aliases TEXT DEFAULT NULL;
