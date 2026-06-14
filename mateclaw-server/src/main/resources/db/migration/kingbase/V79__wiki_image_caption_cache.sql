-- mate_wiki_image_caption_cache: SHA-256 keyed image caption store.
--
-- Cache is shared across all knowledge bases — the same image bytes
-- uploaded twice (in different KBs, by different users, or to the same
-- KB at different times) cost exactly one vision-LLM call total.
--
-- The cache is content-addressed by raw image bytes; perceptual variations
-- (re-encoded JPEG, slightly cropped) are intentionally treated as misses.
-- A second-tier perceptual hash can be added later if the miss rate
-- becomes a cost concern.

CREATE TABLE IF NOT EXISTS mate_wiki_image_caption_cache (
    id              BIGINT  PRIMARY KEY,
    image_sha256    CHAR(64)     NOT NULL,

    caption         TEXT         NOT NULL,
    visible_text    TEXT,
    mime_type       VARCHAR(64),

    capture_model   VARCHAR(128) NOT NULL,
    provider_id     VARCHAR(64)  NOT NULL,

    duration_ms     BIGINT,
    hit_count       BIGINT       NOT NULL DEFAULT 0,

    captured_at     TIMESTAMP(3)  NOT NULL,
    create_time     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted         SMALLINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_wicc_sha ON mate_wiki_image_caption_cache (image_sha256);
CREATE INDEX IF NOT EXISTS idx_wicc_captured ON mate_wiki_image_caption_cache (captured_at);
