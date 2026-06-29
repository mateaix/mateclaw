-- V161: Context Intelligence v2 — mate_model_context_state table (H2).
--
-- H2 accepts the same column layout as MySQL. Differences:
--   1. TINYINT(1) -> BOOLEAN
--   2. DATETIME(3) -> TIMESTAMP (H2's TIMESTAMP has sub-second precision)
-- See mysql/V161__mate_model_context_state.sql for full design notes.

CREATE TABLE IF NOT EXISTS mate_model_context_state (
    provider_id         VARCHAR(128)  NOT NULL,
    model_name          VARCHAR(256)  NOT NULL,
    phase               VARCHAR(32)   NOT NULL,
    effective_window    INT           NOT NULL DEFAULT 0,
    confidence_lower    INT           NOT NULL DEFAULT 0,
    confidence_upper    INT           NOT NULL DEFAULT 0,
    declared_limit      INT           NOT NULL DEFAULT 0,
    peak_observed       INT           NOT NULL DEFAULT 0,
    successive_success  INT           NOT NULL DEFAULT 0,
    successive_overflow INT           NOT NULL DEFAULT 0,
    total_success       INT           NOT NULL DEFAULT 0,
    total_overflow      INT           NOT NULL DEFAULT 0,
    last_success_at     TIMESTAMP     NULL,
    last_overflow_at    TIMESTAMP     NULL,
    last_updated_at     TIMESTAMP     NULL,
    is_diversity        BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (provider_id, model_name)
);
