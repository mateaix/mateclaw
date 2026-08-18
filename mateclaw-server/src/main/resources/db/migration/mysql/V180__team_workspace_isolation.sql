-- Bind every team to one workspace. Existing teams inherit the lead agent's
-- workspace; rows with a missing/legacy lead remain in the default workspace.
SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_agent_team'
             AND COLUMN_NAME = 'workspace_id');
SET @s := IF(@c = 0,
    'ALTER TABLE mate_agent_team ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 AFTER description',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mate_agent_team t
JOIN mate_agent a ON a.id = t.lead_agent_id
SET t.workspace_id = a.workspace_id
WHERE a.workspace_id IS NOT NULL;

SET @c := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
           WHERE TABLE_SCHEMA = DATABASE()
             AND TABLE_NAME = 'mate_agent_team'
             AND INDEX_NAME = 'idx_agent_team_workspace');
SET @s := IF(@c = 0,
    'CREATE INDEX idx_agent_team_workspace ON mate_agent_team (workspace_id, create_time)',
    'SELECT 1');
PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
