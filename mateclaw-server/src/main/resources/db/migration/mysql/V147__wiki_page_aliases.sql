-- V147: Page aliases — MySQL dialect.
--
-- See h2/V147__wiki_page_aliases.sql for column semantics. MySQL needs an
-- INFORMATION_SCHEMA guard because ADD COLUMN IF NOT EXISTS is unavailable on
-- the older 8.0.x versions the deploy targets.
SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'aliases'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN aliases JSON DEFAULT NULL COMMENT ''Alternate concept names this page also covers''',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
