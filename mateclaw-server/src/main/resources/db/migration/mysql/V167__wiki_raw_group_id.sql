-- V167: link raw materials to a source group (V166). See h2/V167 for notes.
-- MySQL lacks ADD COLUMN/CREATE INDEX IF NOT EXISTS, so both are guarded by
-- an INFORMATION_SCHEMA check + prepared statement (idempotent; project
-- convention, see V146/V161).
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND COLUMN_NAME = 'group_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_raw_material ADD COLUMN group_id BIGINT DEFAULT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_raw_material' AND INDEX_NAME = 'idx_wiki_raw_group');
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_wiki_raw_group ON mate_wiki_raw_material(group_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
