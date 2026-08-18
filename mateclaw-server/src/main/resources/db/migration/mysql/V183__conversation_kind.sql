SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_conversation'
             AND COLUMN_NAME = 'conversation_kind');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_conversation ADD COLUMN conversation_kind VARCHAR(32) NOT NULL DEFAULT ''primary''',
    'SELECT 1');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
