-- RFC-063r §2.9: bind a cron job to its originating channel (MySQL dialect).

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_cron_job' AND column_name = 'channel_id'
    ) THEN
        ALTER TABLE mate_cron_job ADD COLUMN channel_id BIGINT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_cron_job' AND column_name = 'delivery_config'
    ) THEN
        ALTER TABLE mate_cron_job ADD COLUMN delivery_config JSONB;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_cron_channel ON mate_cron_job (channel_id);
