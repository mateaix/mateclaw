-- V161: Context Intelligence v2 — mate_model_context_state table.
--
-- Backs the WindowProbe state machine (vip.mate.context.intelligence.probe).
-- Each (provider_id, model_name) pair has at most one row storing the probe's
-- learned window bounds + statistics so that restarts do not lose learning.
--
-- Why a new table instead of reusing v1's mate_model_context_state:
--   The user reset the codebase before v2 was built, so v1's table was never
--   created in any prior migration. This V161 creates it from scratch with
--   the v2 schema (design doc §5.6).
--
-- Column notes:
--   * phase — WindowProbe.Phase enum name (COLD/PROBING/BINARY_SEARCH/STABLE/DEGRADED)
--   * declared_limit — model-declared max context (0 = unknown)
--   * is_diversity — reserved for BackendDiversityTracker.diversityDetected
--     persistence (design doc §5.6). Not actively written/read by the current
--     repository; DEFAULT 0 keeps it safe until the restore path is wired.
--   * last_*_at — nullable because COLD-start probes have no timestamps yet.

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
    last_success_at     DATETIME(3)   NULL,
    last_overflow_at    DATETIME(3)   NULL,
    last_updated_at     DATETIME(3)   NULL,
    is_diversity        TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (provider_id, model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
