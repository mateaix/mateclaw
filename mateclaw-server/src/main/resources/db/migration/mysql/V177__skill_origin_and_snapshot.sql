-- V177: Curation provenance + pre-sweep snapshots.
--
-- 1. mate_skill.origin — authorship as a policy flag ('user' | 'agent' |
--    'routine'). source_conversation_id alone cannot gate curation: a skill
--    the user asked for in a live conversation carries one just like a skill
--    the background reviewer invented, so the curator had no way to tell them
--    apart and aged both on the same clock.
--
--    Backfill deliberately stamps every existing conversation-sourced skill as
--    'agent', which reproduces the curator's current candidate set exactly, so
--    upgrading changes no behaviour. Authorship of those rows is genuinely
--    unrecoverable — it is not inferred, it is preserved. Only writes from
--    this version forward carry a true value.
--
-- 2. mate_skill_snapshot — a restore point captured before each mutating
--    sweep. Consolidation rewrites skill bodies and archival moves them out of
--    the active set; both were previously one-way.
--
-- MySQL lacks `ADD COLUMN IF NOT EXISTS` and `CREATE INDEX IF NOT EXISTS`;
-- both use INFORMATION_SCHEMA guards with PREPARE/EXECUTE instead.

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill' AND COLUMN_NAME = 'origin');
SET @s := IF(@c = 0, 'ALTER TABLE mate_skill ADD COLUMN origin VARCHAR(16) DEFAULT NULL', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mate_skill SET origin = 'agent'
    WHERE origin IS NULL AND source_conversation_id IS NOT NULL;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mate_skill' AND INDEX_NAME = 'idx_skill_origin');
SET @s := IF(@c = 0, 'CREATE INDEX idx_skill_origin ON mate_skill (origin)', 'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS mate_skill_snapshot (
    id            BIGINT       NOT NULL PRIMARY KEY,
    -- Why the snapshot was taken ('pre-sweep', 'pre-restore', or a manual note).
    reason        VARCHAR(255),
    skill_count   INT          NOT NULL DEFAULT 0,
    -- JSON array of the captured skill rows.
    payload       LONGTEXT,
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       INT          NOT NULL DEFAULT 0,
    KEY idx_skill_snapshot_created (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Restore points captured before mutating curator sweeps';
