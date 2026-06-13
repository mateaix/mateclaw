-- Add DeepSeek V4 (flash + pro) model entries for MySQL deployments.
-- Cross-dialect parity with h2/V45 — see that file's header for context.

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
(1000000282, 'DeepSeek V4 Flash', 'deepseek', 'deepseek-v4-flash', 'DeepSeek V4 Flash (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, 1, 1, 0, NOW(), NOW(), 0),
(1000000283, 'DeepSeek V4 Pro', 'deepseek', 'deepseek-v4-pro', 'DeepSeek V4 Pro (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, 1, 1, 0, NOW(), NOW(), 0)
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
