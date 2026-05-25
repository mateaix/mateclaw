-- Output format declared on the template. See h2 sibling for the prose
-- explanation. MySQL needs the INFORMATION_SCHEMA guard pattern.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_transformation'
             AND COLUMN_NAME = 'output_format');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_wiki_transformation ADD COLUMN output_format VARCHAR(16) NOT NULL DEFAULT ''markdown''',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
