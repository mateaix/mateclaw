-- Issue #50 follow-up: see h2/V70 for rationale. MySQL doesn't allow
-- DELETE with a subquery scanning the same table directly, so use
-- the LEFT JOIN + IS NULL pattern.

-- Step 1: physically purge any deleted=1 rows.
DELETE FROM mate_cron_job WHERE deleted = 1;

-- Step 2: idempotent re-dedup against active rows only.
DELETE FROM mate_cron_job
WHERE deleted = 0
  AND id NOT IN (
    SELECT MIN(id)
    FROM mate_cron_job
    WHERE deleted = 0
    GROUP BY workspace_id, agent_id, name
  );
