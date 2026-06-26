-- RFC-063r §2.9: cron run delivery state machine (MySQL dialect).
-- See V57 H2 file for state-machine + design reasoning.
--
-- MySQL has no ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS —
-- guard each statement via INFORMATION_SCHEMA + dynamic SQL (project pattern
-- previously used in V4/V19/V44).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_cron_job_run' AND column_name = 'delivery_status'
    ) THEN
        ALTER TABLE mate_cron_job_run ADD COLUMN delivery_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_cron_job_run' AND column_name = 'delivery_target'
    ) THEN
        ALTER TABLE mate_cron_job_run ADD COLUMN delivery_target VARCHAR(512);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_cron_job_run' AND column_name = 'delivery_error'
    ) THEN
        ALTER TABLE mate_cron_job_run ADD COLUMN delivery_error VARCHAR(500);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_cron_run_pending_started ON mate_cron_job_run (delivery_status, started_at);
