-- Optional target pageType for a transformation whose output_target='page'.
-- See the h2 sibling migration for the prose explanation. MySQL lacks
-- `ADD COLUMN IF NOT EXISTS`, so the column is guarded by an
-- INFORMATION_SCHEMA check + prepared statement.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_transformation'
             AND COLUMN_NAME = 'target_page_type');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_wiki_transformation ADD COLUMN target_page_type VARCHAR(64) DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
