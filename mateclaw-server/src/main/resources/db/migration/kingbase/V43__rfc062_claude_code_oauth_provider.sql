-- RFC-062: Seed the Anthropic Claude Code OAuth provider + its Claude 4.7
-- model bindings on existing deployments. New installs already get these
-- rows from data-mysql-{en,zh}.sql via DatabaseBootstrapRunner; this
-- migration is for operators upgrading from <= V42.
--
-- INSERT ... ON CONFLICT DO UPDATE is the PostgreSQL idempotent upsert.
-- Same V number is used in h2/ for cross-dialect parity.

INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, create_time, update_time)
VALUES ('anthropic-claude-code', 'Anthropic Claude Code (OAuth)', '', 'ClaudeCodeChatModel', '', 'https://api.anthropic.com', '{}', 0, 0, 0, 0, 1, 0, 'oauth', NOW(), NOW())
ON CONFLICT (provider_id) DO UPDATE SET name = EXCLUDED.name,
    chat_model = EXCLUDED.chat_model,
    base_url = EXCLUDED.base_url,
    auth_type = EXCLUDED.auth_type,
    update_time = EXCLUDED.update_time;

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
(1000000280, 'Claude Opus 4.7', 'anthropic-claude-code', 'claude-opus-4-7', 'Claude Opus 4.7 via Claude Code Pro/Max subscription', NULL, 4096, NULL, 1, 1, 0, NOW(), NOW(), 0),
(1000000281, 'Claude Sonnet 4.7', 'anthropic-claude-code', 'claude-sonnet-4-7', 'Claude Sonnet 4.7 via Claude Code Pro/Max subscription', NULL, 4096, NULL, 1, 1, 0, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
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
