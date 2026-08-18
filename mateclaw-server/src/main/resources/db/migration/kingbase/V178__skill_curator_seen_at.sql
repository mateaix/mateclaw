-- V178: Record when curation first saw a skill, separately from when it was created.
--
-- The idle clock anchored on create_time, which conflated two different
-- moments: when a skill was written, and when it first fell under curation.
-- For a skill that only becomes eligible later — widening the curator scope
-- brings a whole library in at once — those differ by however long the skill
-- existed unmanaged, so it entered curation already looking years idle and was
-- archived on the very next sweep.
--
-- The backfill covers only rows already inside the default AGENT_CREATED scope,
-- so their clocks continue exactly as before and upgrading changes nothing.
-- Rows outside the scope stay NULL and get seeded the first time a sweep
-- actually sees them.

ALTER TABLE mate_skill ADD COLUMN IF NOT EXISTS curator_seen_at TIMESTAMP(3);

UPDATE mate_skill SET curator_seen_at = create_time
    WHERE curator_seen_at IS NULL AND origin IN ('agent', 'routine');
