-- V134: KB-scoped pageType profile + structured page metadata columns.
-- See the H2 file for the design rationale. MySQL 8 uses a VIRTUAL generated
-- column for the "one enabled profile per KB" constraint, and an
-- INFORMATION_SCHEMA guard for each idempotent ADD COLUMN (MySQL has no
-- ADD COLUMN IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS mate_wiki_page_type_profile (
    id            BIGINT       NOT NULL,
    kb_id         BIGINT       NOT NULL,
    name          VARCHAR(128) NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    config_json   LONGTEXT     NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    deleted       INT          NOT NULL DEFAULT 0,
    -- Yields kb_id only for the live-enabled row; NULL otherwise. InnoDB
    -- ignores NULL keys for uniqueness, giving "at most one enabled per KB".
    enabled_kb    BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN enabled = 1 AND deleted = 0 THEN kb_id ELSE NULL END
        ) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wiki_ptprofile_name (kb_id, name, deleted),
    UNIQUE KEY uk_wiki_ptprofile_enabled (enabled_kb),
    KEY idx_wiki_ptprofile_kb (kb_id, enabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Structured page metadata columns (idempotent adds).
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'metadata_json');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN metadata_json LONGTEXT', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'metadata_validation_status');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN metadata_validation_status VARCHAR(32)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'metadata_validation_json');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN metadata_validation_json LONGTEXT', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'template_key');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN template_key VARCHAR(128)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_wiki_page'
      AND COLUMN_NAME = 'profile_version');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_wiki_page ADD COLUMN profile_version INT', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
