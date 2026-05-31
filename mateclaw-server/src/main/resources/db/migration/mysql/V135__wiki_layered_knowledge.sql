-- V135: Layered knowledge (fact / experience) + page dependency graph.
-- See the H2 file for rationale. MySQL uses INFORMATION_SCHEMA guards for the
-- idempotent column adds.

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'knowledge_layer');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN knowledge_layer VARCHAR(16)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'depends_on_json');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN depends_on_json LONGTEXT', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'stale');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN stale TINYINT NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'stale_reason_json');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN stale_reason_json LONGTEXT', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS mate_wiki_page_dependency (
    id                  BIGINT      NOT NULL PRIMARY KEY,
    kb_id               BIGINT      NOT NULL,
    page_id             BIGINT      NOT NULL,
    depends_on_page_id  BIGINT      NOT NULL,
    dependency_type     VARCHAR(32) NOT NULL DEFAULT 'fact',
    create_time         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted             INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uk_wiki_page_dep (page_id, depends_on_page_id, dependency_type, deleted),
    KEY idx_wiki_page_dep_reverse (kb_id, depends_on_page_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
