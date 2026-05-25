-- Record per-run token usage. See h2 sibling for prose explanation.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_transformation_run'
             AND COLUMN_NAME = 'input_tokens');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_transformation_run ADD COLUMN input_tokens BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_transformation_run'
             AND COLUMN_NAME = 'output_tokens');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_transformation_run ADD COLUMN output_tokens BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_transformation_run'
             AND COLUMN_NAME = 'total_tokens');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_transformation_run ADD COLUMN total_tokens BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
