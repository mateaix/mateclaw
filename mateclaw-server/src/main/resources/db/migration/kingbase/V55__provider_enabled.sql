-- V55 (RFC-074): explicit user-enabled flag on providers. See H2 sibling for
-- the full rationale; this file only differs in dialect-specific syntax.
--
-- MySQL lacks ADD COLUMN IF NOT EXISTS and CREATE INDEX IF NOT EXISTS —
-- guard via INFORMATION_SCHEMA + dynamic SQL so re-runs are no-ops.

-- ── Add enabled column ───────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_model_provider' AND column_name = 'enabled'
    ) THEN
        ALTER TABLE mate_model_provider ADD COLUMN enabled BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

-- ── Index supporting Rule 3's 30-day usage lookup ──────────────────────────
CREATE INDEX IF NOT EXISTS idx_message_runtime_provider_time ON mate_message (runtime_provider, create_time);

-- ── Rule 1: real (non-masked, non-empty) API key → user is using it ────────
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE api_key IS NOT NULL AND api_key <> '' AND POSITION('*' IN api_key) = 0;

-- ── Rule 2: OAuth provider with token → user is using it ───────────────────
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE oauth_access_token IS NOT NULL AND oauth_access_token <> '';

-- ── Rule 3: local provider with messages in last 30 days → user is using it ─
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE is_local = 1
   AND provider_id IN (
     SELECT DISTINCT runtime_provider
       FROM mate_message
      WHERE runtime_provider IS NOT NULL
        AND create_time >= CURRENT_TIMESTAMP - INTERVAL '30 days'
   );

-- ── Rule 4: provider whose model is the current default → user is using it ─
UPDATE mate_model_provider
   SET enabled = TRUE
 WHERE provider_id IN (SELECT provider FROM mate_model_config WHERE is_default = 1);
