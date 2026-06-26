-- V12: Wiki chunk persistence (RFC-013 minimal slice → enables RFC-011 embedding)
CREATE TABLE IF NOT EXISTS mate_wiki_chunk (
    id              BIGINT       NOT NULL PRIMARY KEY,
    kb_id           BIGINT       NOT NULL,
    raw_id          BIGINT       NOT NULL,
    ordinal         INT          NOT NULL,
    content         TEXT         NOT NULL,
    char_count      INT          NOT NULL,
    start_offset    INT          NOT NULL,
    end_offset      INT          NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,
    create_time     TIMESTAMP     NOT NULL,
    update_time     TIMESTAMP     NOT NULL,
    deleted         INT          NOT NULL DEFAULT 0
);

-- MySQL lacks CREATE INDEX IF NOT EXISTS; use INFORMATION_SCHEMA.STATISTICS guard instead.
CREATE INDEX IF NOT EXISTS idx_wiki_chunk_kb ON mate_wiki_chunk (kb_id);

CREATE INDEX IF NOT EXISTS idx_wiki_chunk_raw ON mate_wiki_chunk (raw_id);

CREATE INDEX IF NOT EXISTS idx_wiki_chunk_hash ON mate_wiki_chunk (content_hash);
