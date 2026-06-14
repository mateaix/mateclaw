-- Add Claude Opus 4.8 (regular + -fast variant) model entries to
-- mate_model_config for existing deployments. New installs pick these up via
-- DatabaseBootstrapRunner from data-mysql-{en,zh}.sql; this migration covers
-- operators who already have earlier Flyway versions applied.
--
-- Claude 4.8 inherits 4.7's strict API contract: temperature / top_p / top_k
-- must be NULL (otherwise HTTP 400), and the "xhigh" thinking tier is
-- available. Both are handled in AnthropicChatModelBuilder via the
-- isClaude47OrLater() detector.
--
-- INSERT ... ON CONFLICT DO UPDATE is the PostgreSQL idempotent upsert.
-- Same V number is used in h2/ for cross-dialect parity.

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
-- Direct Anthropic
(1000000290, 'Claude Opus 4.8', 'anthropic', 'claude-opus-4-8', 'Anthropic Claude Opus 4.8 (xhigh adaptive thinking)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000291, 'Claude Opus 4.8 Fast', 'anthropic', 'claude-opus-4-8-fast', 'Claude Opus 4.8 fast variant (higher output speed, 2x pricing)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- OpenRouter passthrough
(1000000292, 'Claude Opus 4.8', 'openrouter', 'anthropic/claude-opus-4-8', 'Claude Opus 4.8 via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000293, 'Claude Opus 4.8 Fast', 'openrouter', 'anthropic/claude-opus-4-8-fast', 'Claude Opus 4.8 fast variant via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- Claude Code OAuth (Pro/Max subscription)
(1000000294, 'Claude Opus 4.8', 'anthropic-claude-code', 'claude-opus-4-8', 'Claude Opus 4.8 via Claude Code Pro/Max subscription', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    provider = EXCLUDED.provider,
    model_name = EXCLUDED.model_name,
    description = EXCLUDED.description,
    temperature = EXCLUDED.temperature,
    max_tokens = EXCLUDED.max_tokens,
    top_p = EXCLUDED.top_p,
    builtin = EXCLUDED.builtin,
    enabled = EXCLUDED.enabled,
    is_default = EXCLUDED.is_default,
    update_time = EXCLUDED.update_time,
    deleted = EXCLUDED.deleted;
