-- V129: Persisted wikilink lint state — MySQL dialect.
--
-- See h2/V129__wiki_page_broken_links.sql for column semantics. The MySQL
-- variant needs INFORMATION_SCHEMA guards because MySQL doesn't support
-- ADD COLUMN IF NOT EXISTS prior to 8.0.29 and the deploy targets older
-- supported versions.
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'broken_links'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN broken_links JSON DEFAULT NULL COMMENT ''Outlink targets present in content but not in this KB''',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'broken_links_scanned_at'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN broken_links_scanned_at DATETIME(3) DEFAULT NULL COMMENT ''Timestamp of last broken_links recompute''',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
