-- V162: structured error_code on wiki raw material.
-- The processing pipeline already classifies failures into a stable vocabulary
-- (AUTH_ERROR / BILLING / MODEL_NOT_FOUND / RATE_LIMIT / TIMEOUT / SERVER_ERROR /
-- CONTENT_FILTER / UNKNOWN, see WikiProcessingService#classifyErrorCode) but only
-- the free-text error_message reached the raw_material row — so the frontend could
-- not localize the failure into a user-friendly hint. Persisting the code lets the
-- UI render a friendly i18n message and keep the raw message as a collapsible detail.
-- Nullable: NULL = no error (or a legacy failure recorded before this column existed).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_raw_material' AND column_name = 'error_code'
    ) THEN
        ALTER TABLE mate_wiki_raw_material ADD COLUMN error_code VARCHAR(64) DEFAULT NULL;
    END IF;
END $$;
