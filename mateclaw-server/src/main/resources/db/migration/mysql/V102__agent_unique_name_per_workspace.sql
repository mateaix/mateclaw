-- Enforce unique Agent name within a workspace. See H2 variant for context.
--
-- Step 1 — rename pre-existing duplicates. MySQL forbids referencing the
-- target table in a subquery for UPDATE, so we use a self-join with a
-- derived "min id per group" table to pick which row keeps the original
-- name (the oldest by id) and rename the rest.
--
-- The rename target drops the original name and substitutes
-- `__mate_dup_v102__<id>__<uuid>`. Any deterministic transformation of
-- the original name has a non-zero collision risk against a hand-typed
-- pre-existing row that happens to match the pattern (e.g. someone named
-- their agent `foo__v102_dup__2`). A random UUID component drives the
-- collision probability to ~1/2^122 — provably unique for a one-shot
-- migration. Mirrors the H2 variant via MySQL's UUID() function.
UPDATE mate_agent t
JOIN (
    SELECT workspace_id, name, MIN(id) AS keep_id
    FROM mate_agent
    GROUP BY workspace_id, name
    HAVING COUNT(*) > 1
) k
  ON t.workspace_id = k.workspace_id
 AND t.name = k.name
 AND t.id <> k.keep_id
SET t.name = CONCAT('__mate_dup_v102__', t.id, '__', UUID());

-- Step 2 — add the unique index, idempotent via INFORMATION_SCHEMA guard
-- (matches the V69 cron-job pattern; works on MySQL < 8.0.29 which has no
-- CREATE INDEX IF NOT EXISTS).
SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'mate_agent'
                      AND INDEX_NAME = 'uk_agent_workspace_name');
SET @stmt := IF(@idx_exists = 0,
                'CREATE UNIQUE INDEX uk_agent_workspace_name ON mate_agent(workspace_id, name)',
                'SELECT 1');
PREPARE s FROM @stmt; EXECUTE s; DEALLOCATE PREPARE s;
