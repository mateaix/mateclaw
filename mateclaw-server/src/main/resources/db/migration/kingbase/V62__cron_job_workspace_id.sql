-- RFC-083: workspace-isolate cron jobs
-- (issue: https://github.com/matevip/mateclaw/issues/37).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_cron_job' AND column_name = 'workspace_id'
    ) THEN
        ALTER TABLE mate_cron_job ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_cron_job_workspace ON mate_cron_job (workspace_id, deleted);
