-- RFC-009 Phase 1: ordered multi-provider fallback chain
--
-- fallback_priority defines the order in which a provider is tried after the
-- primary model exhausts retries:
--   0       : not in the fallback chain (default — matches pre-RFC behavior)
--   1, 2, … : try in ascending order
--
-- MySQL lacks ADD COLUMN IF NOT EXISTS; use the INFORMATION_SCHEMA guard so
-- this migration is idempotent across redeploys.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'fallback_priority'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN fallback_priority INT DEFAULT 0;
    END IF;
END $$;

-- Seed: keep DashScope as priority 1 so existing deployments preserve the
-- single-fallback-to-DashScope behavior the hardcoded path used to provide.
UPDATE mate_model_provider
   SET fallback_priority = 1
 WHERE provider_id = 'dashscope'
   AND (fallback_priority IS NULL OR fallback_priority = 0);
