-- Optional JSON Schema column. See h2 sibling for the prose explanation.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_transformation'
             AND COLUMN_NAME = 'output_schema');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_transformation ADD COLUMN output_schema MEDIUMTEXT DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
