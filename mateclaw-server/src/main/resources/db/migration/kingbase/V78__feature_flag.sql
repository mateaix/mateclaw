-- mate_feature_flag: runtime-toggleable feature flag store.
--
-- Each row defines one named flag with optional KB / user whitelists and
-- a percentage rollout. Reads go through an in-memory cache that refreshes
-- on a 30-second timer (and immediately on admin write); the cache is
-- per-instance so multi-instance deployments converge within one tick.

CREATE TABLE IF NOT EXISTS mate_feature_flag (
    id                  BIGSERIAL  PRIMARY KEY,
    flag_key            VARCHAR(128) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT 0,
    description         VARCHAR(512),
    whitelist_kb_ids    TEXT,
    whitelist_user_ids  TEXT,
    rollout_percent     INT          DEFAULT 0,

    create_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted             SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_mff_key ON mate_feature_flag (flag_key);
CREATE INDEX IF NOT EXISTS idx_mff_key_flag ON mate_feature_flag (flag_key, enabled, deleted);

-- Seed wiki feature flags with safe defaults. ON DUPLICATE KEY UPDATE
-- preserves existing operator overrides on re-run.
INSERT INTO mate_feature_flag (flag_key, enabled, description) VALUES
    ('wiki.ocr.enabled',                0, 'Image OCR / vision-in pipeline for wiki uploads'),
    ('wiki.compile.4stage.enabled',     0, 'Four-stage knowledge base compilation pipeline'),
    ('wiki.compile.cache.enabled',      0, 'Prompt cache layer for the wiki compile pipeline'),
    ('wiki.confidence.enabled',         0, 'Confidence taxonomy on wiki relations and pages'),
    ('wiki.hot_cache.enabled',          0, 'KB-level recent-activity snapshot injected into agent system prompt'),
    ('wiki.graph.insights.enabled',     0, 'Wiki graph insights panel (surprising connections, gaps, bridges)'),
    ('wiki.graph.adamic_adar.enabled',  0, 'Adamic-Adar graph signal (additive to existing four signals)'),
    ('wiki.graph.boundary.enabled',     0, 'Boundary score for surfacing dangling pages'),
    ('wiki.relation.cache.enabled',     1,  'Persistent cache for wiki page-to-page relation computation')
ON CONFLICT (flag_key) DO UPDATE SET description = EXCLUDED.description;
