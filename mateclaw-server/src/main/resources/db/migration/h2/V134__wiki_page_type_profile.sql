-- V134: KB-scoped pageType profile + structured page metadata columns.
--
-- A profile holds a KB's pageType definitions (field schema, per-stage LLM
-- instructions, Markdown template) as config_json. The built-in default
-- profile is NOT stored here — it lives as a code constant — so kb_id is
-- NOT NULL and every stored row belongs to a concrete KB.
--
-- "At most one enabled profile per KB" is enforced at the DB level via a
-- virtual generated column that yields kb_id only for the live-enabled
-- subset (NULL otherwise) plus a plain UNIQUE constraint; NULLs are
-- non-comparable under UNIQUE, so disabled/deleted rows coexist. This avoids
-- a service-layer check-then-insert race across horizontally-scaled nodes.

CREATE TABLE IF NOT EXISTS mate_wiki_page_type_profile (
    id            BIGINT       NOT NULL,
    kb_id         BIGINT       NOT NULL,
    name          VARCHAR(128) NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    config_json   CLOB         NOT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          NOT NULL DEFAULT 0,
    -- Yields kb_id only for the live-enabled row; NULL otherwise. The UNIQUE
    -- constraint below then permits at most one enabled profile per KB.
    enabled_kb    BIGINT GENERATED ALWAYS AS (
        CASE WHEN enabled = 1 AND deleted = 0 THEN kb_id ELSE NULL END
    ),
    PRIMARY KEY (id),
    CONSTRAINT uk_wiki_ptprofile_name UNIQUE (kb_id, name, deleted),
    CONSTRAINT uk_wiki_ptprofile_enabled UNIQUE (enabled_kb)
);
CREATE INDEX IF NOT EXISTS idx_wiki_ptprofile_kb
    ON mate_wiki_page_type_profile (kb_id, enabled, deleted);

-- Structured page metadata: schema-validated pageType fields live in
-- metadata_json (not exploded into columns); validation status/details and
-- the generating profile/template are recorded alongside.
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS metadata_json CLOB;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS metadata_validation_status VARCHAR(32);
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS metadata_validation_json CLOB;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS template_key VARCHAR(128);
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS profile_version INT;
