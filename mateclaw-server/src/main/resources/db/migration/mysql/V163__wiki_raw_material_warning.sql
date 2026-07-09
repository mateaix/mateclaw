-- V163: non-blocking warning surface on wiki raw material.
-- Some ingest sub-steps run async *after* the material is already marked
-- completed/partial — embedding (semantic search) and entity-graph extraction.
-- When they fail the material stays "completed" but is silently degraded
-- (e.g. not searchable), and previously the only trace was a server log line.
-- These columns let such a failure show as a non-blocking warning on an
-- otherwise-successful row. Mirrors the error_code/error_message pair so the
-- UI can render a localized friendly hint; NULL = no warning.
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'warning_code');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN warning_code VARCHAR(64) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'warning_message');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_raw_material ADD COLUMN warning_message VARCHAR(512) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
