CREATE TABLE IF NOT EXISTS mate_wiki_processing_job (
    id                  BIGINT  PRIMARY KEY,
    kb_id               BIGINT       NOT NULL,
    raw_id              BIGINT       NOT NULL,
    job_type            VARCHAR(32)  NOT NULL DEFAULT 'heavy_ingest',
    stage               VARCHAR(64)  NOT NULL DEFAULT 'queued',
    status              VARCHAR(32)  NOT NULL DEFAULT 'queued',
    primary_model_id    BIGINT,
    current_model_id    BIGINT,
    fallback_chain_json TEXT,
    retry_count         INT          NOT NULL DEFAULT 0,
    max_retries         INT          NOT NULL DEFAULT 3,
    error_code          VARCHAR(64),
    error_message       TEXT,
    resume_from_stage   VARCHAR(64),
    meta_json           TEXT,
    started_at          TIMESTAMP(3),
    finished_at         TIMESTAMP(3),
    create_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP ,
    deleted             SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_wpj_raw ON mate_wiki_processing_job (raw_id);
CREATE INDEX IF NOT EXISTS idx_wpj_status ON mate_wiki_processing_job (status);
CREATE INDEX IF NOT EXISTS idx_wpj_kb ON mate_wiki_processing_job (kb_id, status);
