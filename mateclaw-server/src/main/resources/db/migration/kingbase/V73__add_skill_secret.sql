-- V73: Per-skill encrypted secret store (RFC-091 settings bridge).
-- Holds AES-encrypted values for skill manifest fields with type=secret —
-- e.g. AIRTABLE_API_KEY for the airtable-base wizard template. The
-- runtime layer (SkillScriptExecutionService) decrypts and injects these
-- as environment variables when spawning skill subprocesses, so SKILL.md
-- bodies can reference them as plain $AIRTABLE_API_KEY without baking
-- the secret into the manifest.
--
-- MySQL doesn't support ADD COLUMN IF NOT EXISTS; we get idempotency
-- via CREATE TABLE IF NOT EXISTS plus an INFORMATION_SCHEMA guard for
-- index creation.

CREATE TABLE IF NOT EXISTS mate_skill_secret (
    id              BIGINT        NOT NULL PRIMARY KEY,
    skill_id        BIGINT        NOT NULL,
    secret_key      VARCHAR(128)  NOT NULL,
    encrypted_value TEXT          NOT NULL,
    create_time     TIMESTAMP      NOT NULL,
    update_time     TIMESTAMP      NOT NULL,
    deleted         SMALLINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_secret_key ON mate_skill_secret (skill_id, secret_key);
CREATE INDEX IF NOT EXISTS idx_skill_secret_skill ON mate_skill_secret (skill_id);
