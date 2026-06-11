-- Per-KB source-watcher toggle. See the h2 sibling for the prose explanation.
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`, so the column is guarded by an
-- INFORMATION_SCHEMA check + prepared statement (idempotent).

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_wiki_knowledge_base'
             AND COLUMN_NAME = 'watcher_enabled');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_wiki_knowledge_base ADD COLUMN watcher_enabled TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
