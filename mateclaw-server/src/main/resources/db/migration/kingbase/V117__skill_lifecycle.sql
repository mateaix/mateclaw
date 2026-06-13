-- See the H2 file for context. KingbaseES (PostgreSQL) supports
-- both ADD COLUMN IF NOT EXISTS and CREATE INDEX IF NOT EXISTS natively.
--
-- Column types: archived_at / last_activity_at use TIMESTAMP(3) to match
-- mate_skill_usage_stat.last_loaded_at; lifecycle_state VARCHAR(16);
-- pinned SMALLINT.

ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(16) DEFAULT 'active';
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS pinned SMALLINT DEFAULT 0;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP(3) NULL;
ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP(3) NULL;

CREATE INDEX IF NOT EXISTS idx_skill_lifecycle_state ON mate_skill (lifecycle_state);
CREATE INDEX IF NOT EXISTS idx_skill_last_activity_at ON mate_skill (last_activity_at);

-- One-time backfill: existing rows take their newest usage tick as the
-- activity anchor. Rows with no usage stat stay NULL and fall through to
-- create_time at query time via the anchor() helper.
UPDATE mate_skill SET last_activity_at = (
  SELECT MAX(last_loaded_at) FROM mate_skill_usage_stat s
   WHERE s.skill_name = mate_skill.name
)
WHERE last_activity_at IS NULL;

UPDATE mate_skill SET lifecycle_state = 'active' WHERE lifecycle_state IS NULL;
