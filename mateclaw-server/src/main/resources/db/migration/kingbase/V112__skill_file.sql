-- V112: persist skill bundle files (scripts/ + references/) in the database.
--
-- Until now scripts/references only lived on the local filesystem of whichever
-- node handled the upload. Multi-instance deployments sharing one MySQL would
-- have the skill row visible everywhere but the script files only on one node,
-- so any other node attempting to run a skill script either failed or ran a
-- stale local copy. Treating the database as the canonical bundle store and
-- the filesystem as a materialized cache resolves that gap and matches the
-- existing pattern for SKILL.md (canonical in mate_skill.skill_content,
-- mirrored to disk by the workspace manager).
--
-- TEXT (16MB) comfortably covers the per-file 1MB cap enforced by
-- ZipSkillFetcher and the 50MB total bundle cap.

CREATE TABLE IF NOT EXISTS mate_skill_file (
    id            BIGINT       NOT NULL PRIMARY KEY,
    skill_id      BIGINT       NOT NULL,
    file_path     VARCHAR(512) NOT NULL,
    content       TEXT,
    content_size  INT          NOT NULL DEFAULT 0,
    sha256        CHAR(64),
    create_time   TIMESTAMP     NOT NULL,
    update_time   TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_file_path ON mate_skill_file (skill_id, file_path);
CREATE INDEX IF NOT EXISTS idx_skill_file_skill ON mate_skill_file (skill_id);
