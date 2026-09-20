-- Durable liveness for long cron runs (Kingbase/PostgreSQL dialect).
ALTER TABLE mate_cron_job_run ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_cron_run_status_heartbeat
    ON mate_cron_job_run(status, heartbeat_at);
