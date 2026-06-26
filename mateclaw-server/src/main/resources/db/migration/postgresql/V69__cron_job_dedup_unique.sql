-- Issue #50: deduplicate accumulated cron jobs and prevent future duplicates
-- at the DB level. PostgreSQL allows DELETE with subquery on the same table.
--
-- Step 1 — purge duplicate active rows, keeping the earliest id per
-- (workspace_id, agent_id, name). Hard delete because this entity has no
-- @TableLogic; deleteById() already performs physical deletes.
DELETE FROM mate_cron_job
WHERE id NOT IN (
    SELECT MIN(id)
    FROM mate_cron_job
    GROUP BY workspace_id, agent_id, name
);

-- Step 2 — add the unique index, idempotent via INFORMATION_SCHEMA guard
-- (MySQL < 8.0.29 has no CREATE INDEX IF NOT EXISTS).
CREATE UNIQUE INDEX IF NOT EXISTS uk_cron_job_workspace_agent_name ON mate_cron_job (workspace_id, agent_id, name);
