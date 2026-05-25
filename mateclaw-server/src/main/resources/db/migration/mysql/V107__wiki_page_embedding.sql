-- Page-level embedding columns. See the h2 sibling for the prose
-- explanation. MySQL lacks `ADD COLUMN IF NOT EXISTS`, so each column
-- guarded by an INFORMATION_SCHEMA check + prepared statement.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_page'
             AND COLUMN_NAME = 'embedding');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_page ADD COLUMN embedding BLOB DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_page'
             AND COLUMN_NAME = 'embedding_model');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_page ADD COLUMN embedding_model VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_page'
             AND COLUMN_NAME = 'embedding_text_version');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_page ADD COLUMN embedding_text_version VARCHAR(32) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
