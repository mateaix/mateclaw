-- Two-part follow-up to V105 so a transformation's output can flow back
-- into the KB as a first-class artifact. See the h2 sibling migration for
-- the prose explanation. MySQL lacks `ADD COLUMN IF NOT EXISTS`, so each
-- column is guarded by an INFORMATION_SCHEMA check + prepared statement.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_transformation'
             AND COLUMN_NAME = 'output_target');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_wiki_transformation ADD COLUMN output_target VARCHAR(16) NOT NULL DEFAULT ''none''',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_transformation_run'
             AND COLUMN_NAME = 'output_page_id');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_wiki_transformation_run ADD COLUMN output_page_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
