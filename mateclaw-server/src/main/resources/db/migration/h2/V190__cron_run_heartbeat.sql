-- Durable liveness for long cron runs. Stale cleanup falls back to started_at
-- for pre-migration rows whose heartbeat_at is null.
ALTER TABLE mate_cron_job_run ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_cron_run_status_heartbeat
    ON mate_cron_job_run(status, heartbeat_at);
