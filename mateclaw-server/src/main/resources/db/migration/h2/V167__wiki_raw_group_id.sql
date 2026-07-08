-- V167: link raw materials to a source group (V166). NULL = ungrouped
-- (manual text/upload materials, or legacy scanned files not yet re-scanned
-- under their new group).
ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS group_id BIGINT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_wiki_raw_group ON mate_wiki_raw_material(group_id);
