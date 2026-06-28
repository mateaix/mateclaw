-- Per-agent preferred *model* chain (provider + model).
--
-- Extends mate_agent_provider_preference from provider-level to
-- (provider, model)-level: a preference entry may now pin a specific chat
-- model, and the SAME provider may appear multiple times with different
-- models (e.g. A/modelX -> A/modelY -> B/modelZ).
--
-- model_id NULL  = use the provider's default chat model (fully backward
--                  compatible with pre-existing provider-only rows).
-- model_id <id>  = matches mate_model_config.id — pin that exact model.
--
-- The unique key moves from (agent_id, provider_id) to
-- (agent_id, provider_id, model_id) so the same provider can repeat. Note
-- NULLs are treated as distinct in a unique index, so duplicate
-- provider-default rows are not DB-enforced; the service replaces the whole
-- list on save and the UI prevents that, so this is intentional.

-- All three DDL statements below are wrapped in INFORMATION_SCHEMA guards
-- so the migration is idempotent: a mid-migration failure followed by a
-- Flyway repair + re-run will not choke on "column already exists" or
-- "index not found".  MySQL 8.0 lacks native ADD COLUMN IF NOT EXISTS, so
-- the project convention is PREPARE/EXECUTE (see V156, V137).

-- 1) ADD COLUMN model_id (idempotent)
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent_provider_preference' AND COLUMN_NAME = 'model_id');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_agent_provider_preference ADD COLUMN model_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) DROP old unique index uk_agent_provider (only if it exists)
SET @idx_old := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent_provider_preference' AND INDEX_NAME = 'uk_agent_provider');
SET @ddl := IF(@idx_old > 0,
    'ALTER TABLE mate_agent_provider_preference DROP INDEX uk_agent_provider',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) CREATE new unique index uk_agent_provider_model (only if it doesn't exist)
SET @idx_new := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_agent_provider_preference' AND INDEX_NAME = 'uk_agent_provider_model');
SET @ddl := IF(@idx_new = 0,
    'CREATE UNIQUE INDEX uk_agent_provider_model ON mate_agent_provider_preference(agent_id, provider_id, model_id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
