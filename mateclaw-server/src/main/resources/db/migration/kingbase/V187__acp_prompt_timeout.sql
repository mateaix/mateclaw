-- Issue #608: make ACP session/prompt timeout configurable per endpoint.
ALTER TABLE mate_acp_endpoint
    ADD COLUMN IF NOT EXISTS prompt_timeout_seconds INT NOT NULL DEFAULT 300;

UPDATE mate_acp_endpoint
SET prompt_timeout_seconds = 300
WHERE prompt_timeout_seconds IS NULL OR prompt_timeout_seconds <= 0;
