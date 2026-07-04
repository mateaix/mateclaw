-- V166: Per-message token usage detail for the chat consumption breakdown panel.
-- Adds prompt-cache hit/write and reasoning token counters to mate_message so the
-- UI can show input cache hit/miss/write and thinking-vs-reply output splits.
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_message' AND COLUMN_NAME = 'cache_read_tokens');
SET @s := IF(@c = 0, 'ALTER TABLE mate_message ADD COLUMN cache_read_tokens INT DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_message' AND COLUMN_NAME = 'cache_write_tokens');
SET @s := IF(@c = 0, 'ALTER TABLE mate_message ADD COLUMN cache_write_tokens INT DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_message' AND COLUMN_NAME = 'reasoning_tokens');
SET @s := IF(@c = 0, 'ALTER TABLE mate_message ADD COLUMN reasoning_tokens INT DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
