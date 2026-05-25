-- V104: track which input format a chunk's stored embedding was generated against.
-- The embedding input builder concatenates raw title / header breadcrumb / page
-- number alongside chunk content; bumping the builder's CURRENT_INPUT_VERSION
-- forces a re-embed pass without changing the model. NULL is treated as the
-- legacy content-only format and re-embedded lazily on the next pass.
-- MySQL lacks `ADD COLUMN IF NOT EXISTS`; use INFORMATION_SCHEMA guard instead.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_chunk' AND COLUMN_NAME = 'embedding_text_version');
SET @s := IF(@c = 0, 'ALTER TABLE mate_wiki_chunk ADD COLUMN embedding_text_version VARCHAR(32) NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
