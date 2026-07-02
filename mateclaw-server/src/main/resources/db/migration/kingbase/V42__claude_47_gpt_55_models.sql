-- Add Claude 4.7 + GPT-5.5 model entries to mate_model_config for existing
-- deployments. New installs pick these up via DatabaseBootstrapRunner from
-- data-mysql-{en,zh}.sql; this migration covers operators who already have
-- earlier Flyway versions applied.
--
-- INSERT ... ON CONFLICT DO UPDATE is the PostgreSQL idempotent upsert.
-- Same V number is used in h2/ for cross-dialect parity.

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
-- GPT-5.5 series (OpenAI / Azure / OpenRouter)
(1000000260, 'GPT-5.5', 'openai', 'gpt-5.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000261, 'GPT-5.5 Mini', 'openai', 'gpt-5.5-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000262, 'GPT-5.5 Nano', 'openai', 'gpt-5.5-nano', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000263, 'GPT-5.5', 'azure-openai', 'gpt-5.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000264, 'GPT-5.5 Mini', 'azure-openai', 'gpt-5.5-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000265, 'GPT-5.5', 'openrouter', 'openai/gpt-5.5', 'GPT-5.5 via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- Claude 4.7 series. NOTE: Claude 4.7 forbids temperature/top_p/top_k —
-- handled in AgentAnthropicChatModelBuilder. NULL temperature/top_p in seed
-- is the documented signal.
(1000000270, 'Claude Opus 4.7', 'anthropic', 'claude-opus-4-7', 'Anthropic Claude Opus 4.7 (xhigh adaptive thinking)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000271, 'Claude Sonnet 4.7', 'anthropic', 'claude-sonnet-4-7', 'Anthropic Claude Sonnet 4.7', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000272, 'Claude Opus 4.7', 'openrouter', 'anthropic/claude-opus-4-7', 'Claude Opus 4.7 via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000273, 'Claude Sonnet 4.7', 'openrouter', 'anthropic/claude-sonnet-4-7', 'Claude Sonnet 4.7 via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
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
