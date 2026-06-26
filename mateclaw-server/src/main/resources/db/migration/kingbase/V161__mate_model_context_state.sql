-- V161: Context Intelligence v2 — mate_model_context_state table (Kingbase).
--
-- Kingbase accepts BOOLEAN and TIMESTAMP(3). Schema mirrors the H2 layout.
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
    last_success_at     TIMESTAMP(3)  NULL,
    last_overflow_at    TIMESTAMP(3)  NULL,
    last_updated_at     TIMESTAMP(3)  NULL,
    is_diversity        BOOLEAN       NOT NULL DEFAULT FALSE,
    PRIMARY KEY (provider_id, model_name)
);
