-- Repair migration for V42/V43: Anthropic only released Opus 4.7 — there
-- is no claude-sonnet-4-7 model. Calls return HTTP 404 with body
-- {"type":"not_found_error","message":"model: claude-sonnet-4-7"}.
--
-- Anthropic's current model line includes claude-opus-4-7 but no
-- claude-sonnet-4-7. The latest released Sonnet remains claude-sonnet-4-6
-- (released alongside Opus 4.6).
--
-- Strategy: rename in place — preserve ids 1000000271, 1000000273, 1000000281
-- so user-customised settings (default flag, enabled flag) survive.

UPDATE mate_model_config
SET name = 'Claude Sonnet 4.6',
    model_name = 'claude-sonnet-4-6',
    description = 'Anthropic Claude Sonnet 4.6 (latest Sonnet — 4.7 not yet released)',
    update_time = NOW()
WHERE id = 1000000271 AND model_name = 'claude-sonnet-4-7';

UPDATE mate_model_config
SET name = 'Claude Sonnet 4.6',
    model_name = 'anthropic/claude-sonnet-4-6',
    description = 'Claude Sonnet 4.6 via OpenRouter',
    update_time = NOW()
WHERE id = 1000000273 AND model_name = 'anthropic/claude-sonnet-4-7';

UPDATE mate_model_config
SET name = 'Claude Sonnet 4.6',
    model_name = 'claude-sonnet-4-6',
    description = 'Claude Sonnet 4.6 via Claude Code Pro/Max subscription',
    update_time = NOW()
WHERE id = 1000000281 AND model_name = 'claude-sonnet-4-7';
