-- Enforce unique Agent name within a workspace. See H2 variant for context.
--
-- Step 1 — rename pre-existing duplicates. PostgreSQL uses UPDATE ... FROM
-- instead of MySQL's UPDATE ... JOIN syntax, and || instead of CONCAT.
-- md5(random()::text) gives a unique suffix and is portable across both
-- PostgreSQL and KingbaseES (avoids the Kingbase-only SYS_GUID()).
UPDATE mate_agent t
SET name = '__mate_dup_v102__' || t.id || '__' || md5(random()::text)
FROM (
    SELECT workspace_id, name, MIN(id) AS keep_id
    FROM mate_agent
    GROUP BY workspace_id, name
    HAVING COUNT(*) > 1
) k
WHERE t.workspace_id = k.workspace_id
  AND t.name = k.name
  AND t.id <> k.keep_id;

-- Step 2 — add the unique index, idempotent via IF NOT EXISTS (PostgreSQL-native).
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_workspace_name ON mate_agent (workspace_id, name);
