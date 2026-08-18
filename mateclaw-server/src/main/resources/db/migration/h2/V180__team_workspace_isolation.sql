-- Bind every team to one workspace. Existing teams inherit the lead agent's
-- workspace; rows with a missing/legacy lead remain in the default workspace.
ALTER TABLE mate_agent_team
    ADD COLUMN IF NOT EXISTS workspace_id BIGINT NOT NULL DEFAULT 1;

UPDATE mate_agent_team t
SET workspace_id = COALESCE(
    (SELECT a.workspace_id FROM mate_agent a WHERE a.id = t.lead_agent_id),
    1
);

CREATE INDEX IF NOT EXISTS idx_agent_team_workspace
    ON mate_agent_team (workspace_id, create_time);
