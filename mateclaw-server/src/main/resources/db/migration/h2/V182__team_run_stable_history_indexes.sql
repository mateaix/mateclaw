UPDATE mate_team_run
SET create_time = COALESCE(update_time, CURRENT_TIMESTAMP)
WHERE create_time IS NULL;

ALTER TABLE mate_team_run ALTER COLUMN create_time SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_team_run_team_history_stable
    ON mate_team_run (team_id, create_time, id);
CREATE INDEX IF NOT EXISTS idx_team_run_conversation_history_stable
    ON mate_team_run (lead_conversation_id, create_time, id);
