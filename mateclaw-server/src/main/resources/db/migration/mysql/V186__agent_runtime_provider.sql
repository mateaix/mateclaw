-- Add a runtime provider selector without changing the legacy agent_type.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_agent'
             AND COLUMN_NAME = 'runtime_type');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_agent ADD COLUMN runtime_type VARCHAR(32) NOT NULL DEFAULT ''native'' AFTER agent_type',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_agent'
             AND COLUMN_NAME = 'runtime_config');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_agent ADD COLUMN runtime_config TEXT NULL AFTER runtime_type',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mate_agent
SET runtime_type = 'native'
WHERE runtime_type IS NULL OR TRIM(runtime_type) = '';
