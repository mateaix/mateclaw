-- V134: KB-scoped pageType profile + structured page metadata columns.
-- See the H2 file for the design rationale. PostgreSQL/KingbaseES uses a STORED
-- generated column for the "one enabled profile per KB" constraint, and an
-- information_schema guard for each idempotent ADD COLUMN (PostgreSQL has no
-- ADD COLUMN IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS mate_wiki_page_type_profile (
    id            BIGINT       NOT NULL,
    kb_id         BIGINT       NOT NULL,
    name          VARCHAR(128) NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    config_json   TEXT     NOT NULL,
    enabled       BOOLEAN   NOT NULL DEFAULT TRUE,
    create_time   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          NOT NULL DEFAULT 0,
    -- Yields kb_id only for the live-enabled row; NULL otherwise. PostgreSQL
    -- ignores NULL keys for uniqueness, giving "at most one enabled per KB".
    enabled_kb    BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN enabled = TRUE AND deleted = 0 THEN kb_id ELSE NULL END
        ) STORED,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_ptprofile_name ON mate_wiki_page_type_profile (kb_id, name, deleted);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wiki_ptprofile_enabled ON mate_wiki_page_type_profile (enabled_kb);
CREATE INDEX IF NOT EXISTS idx_wiki_ptprofile_kb ON mate_wiki_page_type_profile (kb_id, enabled, deleted);

-- Structured page metadata columns (idempotent adds).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'metadata_json'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN metadata_json TEXT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'metadata_validation_status'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN metadata_validation_status VARCHAR(32);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'metadata_validation_json'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN metadata_validation_json TEXT;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'template_key'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN template_key VARCHAR(128);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_page' AND column_name = 'profile_version'
    ) THEN
        ALTER TABLE mate_wiki_page ADD COLUMN profile_version INT;
    END IF;
END $$;
