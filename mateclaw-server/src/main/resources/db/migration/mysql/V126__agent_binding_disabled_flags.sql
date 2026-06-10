-- V126: Two binding-mode flags on mate_agent (MySQL).
--
-- skills_disabled / tools_disabled flip the "zero binding rows" semantic from
-- "inherit every globally-enabled capability" to "this agent has explicitly
-- opted out". Without these columns, an operator who wanted an agent with no
-- skills had to bind a dummy skill — otherwise the runtime fell back to the
-- global default and every skill's catalog entry got injected into the system
-- prompt (issue #184).
--
-- Idempotent: INFORMATION_SCHEMA guard for each column, since MySQL does not
-- support `ADD COLUMN IF NOT EXISTS`.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'skills_disabled'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN skills_disabled TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_agent'
      AND COLUMN_NAME = 'tools_disabled'
);
SET @stmt := IF(@col_exists = 0,
    'ALTER TABLE mate_agent ADD COLUMN tools_disabled TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
