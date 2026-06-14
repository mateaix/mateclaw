-- V33: Expand mate_model_provider.api_key from VARCHAR(256) to VARCHAR(512)
-- Bailian Token Plan keys exceed 256 chars (observed: 298 chars).
ALTER TABLE mate_model_provider ALTER COLUMN api_key TYPE VARCHAR(512);
