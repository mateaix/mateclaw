-- mate_feature_flag: runtime-toggleable feature flag store.
--
-- Each row defines one named flag with optional KB / user whitelists and
-- a percentage rollout. Reads go through an in-memory cache that refreshes
-- on a 30-second timer (and immediately on admin write); the cache is
-- per-instance so multi-instance deployments converge within one tick.

CREATE TABLE IF NOT EXISTS mate_feature_flag (
    id                  BIGSERIAL  PRIMARY KEY,
    flag_key            VARCHAR(128) NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT FALSE,
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
    ('wiki.ocr.enabled', FALSE, 'Image OCR / vision-in pipeline for wiki uploads'),
    ('wiki.compile.4stage.enabled', FALSE, 'Four-stage knowledge base compilation pipeline'),
    ('wiki.compile.cache.enabled', FALSE, 'Prompt cache layer for the wiki compile pipeline'),
    ('wiki.confidence.enabled', FALSE, 'Confidence taxonomy on wiki relations and pages'),
    ('wiki.hot_cache.enabled', FALSE, 'KB-level recent-activity snapshot injected into agent system prompt'),
    ('wiki.graph.insights.enabled', FALSE, 'Wiki graph insights panel (surprising connections, gaps, bridges)'),
    ('wiki.graph.adamic_adar.enabled', FALSE, 'Adamic-Adar graph signal (additive to existing four signals)'),
    ('wiki.graph.boundary.enabled', FALSE, 'Boundary score for surfacing dangling pages'),
    ('wiki.relation.cache.enabled', TRUE, 'Persistent cache for wiki page-to-page relation computation')
ON CONFLICT (flag_key) DO UPDATE SET description = EXCLUDED.description;
