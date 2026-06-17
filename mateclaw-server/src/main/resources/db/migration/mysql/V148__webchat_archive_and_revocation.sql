-- V148: webchat visitor-session archive flag + visitor-token revocation registry (MySQL).
-- See the H2 copy for full context.

-- 1) archived column — MySQL 8.0 has no ADD COLUMN IF NOT EXISTS.
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_conversation'
      AND COLUMN_NAME = 'archived'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_conversation ADD COLUMN archived INT NOT NULL DEFAULT 0 COMMENT ''webchat: 0 = active, 1 = archived (hidden from default /sessions listing)''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2) revoked-visitor registry
CREATE TABLE IF NOT EXISTS webchat_revoked_visitor (
    id          BIGINT       NOT NULL PRIMARY KEY,
    channel_id  BIGINT       NOT NULL,
    visitor_id  VARCHAR(128) NOT NULL,
    revoked_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason      VARCHAR(255),
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    UNIQUE KEY uk_webchat_revoked_visitor (channel_id, visitor_id, deleted),
    KEY idx_webchat_revoked_visitor_lookup (channel_id, visitor_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
