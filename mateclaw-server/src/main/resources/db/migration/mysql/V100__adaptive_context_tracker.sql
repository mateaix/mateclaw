-- V100__adaptive_context_tracker.sql
CREATE TABLE IF NOT EXISTS mate_model_context_state (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id     VARCHAR(64)  NOT NULL,
    model_name      VARCHAR(128) NOT NULL,
    profile         VARCHAR(32)  NOT NULL DEFAULT 'DYNAMIC_CHAT',
    effective_window    INT NOT NULL DEFAULT 32768,
    confidence_lower    INT NOT NULL DEFAULT 0,
    confidence_upper    INT NOT NULL DEFAULT 65536,
    phase               VARCHAR(16) NOT NULL DEFAULT 'COLD',
    is_gateway          BOOLEAN NOT NULL DEFAULT FALSE,
    peak_observed       INT NOT NULL DEFAULT 0,
    successive_success  INT NOT NULL DEFAULT 0,
    successive_overflow INT NOT NULL DEFAULT 0,
    total_success       INT NOT NULL DEFAULT 0,
    total_overflow      INT NOT NULL DEFAULT 0,
    last_success_at     TIMESTAMP NULL,
    last_overflow_at    TIMESTAMP NULL,
    last_updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_model (provider_id, model_name)
);
