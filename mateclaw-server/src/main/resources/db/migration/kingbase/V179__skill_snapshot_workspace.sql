-- Scope curator restore points to their owning workspace.
ALTER TABLE mate_skill_snapshot
    ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_skill_snapshot_workspace_created
    ON mate_skill_snapshot (workspace_id, create_time);
