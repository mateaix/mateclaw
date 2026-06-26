-- V34: Add SiliconFlow (CN + INTL) and OpenCode providers with preset models
-- SiliconFlow supports model discovery; preset models cover the most popular ones.
-- OpenCode is a free-tier provider with two fixed models.

-- ── Providers ──────────────────────────────────────────────────────────────
INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES ('siliconflow-cn', '硅基流动 (China)', 'sk-', 'OpenAIChatModel', '', 'https://api.siliconflow.cn/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW())
ON CONFLICT (provider_id) DO UPDATE SET name=EXCLUDED.name, api_key_prefix=EXCLUDED.api_key_prefix, chat_model=EXCLUDED.chat_model, base_url=EXCLUDED.base_url, support_model_discovery=EXCLUDED.support_model_discovery, support_connection_check=EXCLUDED.support_connection_check, freeze_url=EXCLUDED.freeze_url, require_api_key=EXCLUDED.require_api_key, update_time=EXCLUDED.update_time;

INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES ('siliconflow-intl', '硅基流动 (International)', 'sk-', 'OpenAIChatModel', '', 'https://api.siliconflow.com/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW())
ON CONFLICT (provider_id) DO UPDATE SET name=EXCLUDED.name, api_key_prefix=EXCLUDED.api_key_prefix, chat_model=EXCLUDED.chat_model, base_url=EXCLUDED.base_url, support_model_discovery=EXCLUDED.support_model_discovery, support_connection_check=EXCLUDED.support_connection_check, freeze_url=EXCLUDED.freeze_url, require_api_key=EXCLUDED.require_api_key, update_time=EXCLUDED.update_time;

INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES ('opencode', 'OpenCode', '', 'OpenAIChatModel', '', 'https://opencode.ai/zen/v1', '{}', FALSE, FALSE, FALSE, TRUE, TRUE, FALSE, NOW(), NOW())
ON CONFLICT (provider_id) DO UPDATE SET name=EXCLUDED.name, chat_model=EXCLUDED.chat_model, base_url=EXCLUDED.base_url, update_time=EXCLUDED.update_time;

-- ── SiliconFlow CN — preset popular models ─────────────────────────────────
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000500, 'DeepSeek V3', 'siliconflow-cn', 'deepseek-ai/DeepSeek-V3', '硅基流动 — DeepSeek V3，综合能力强，有免费额度', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000501, 'DeepSeek R1', 'siliconflow-cn', 'deepseek-ai/DeepSeek-R1', '硅基流动 — DeepSeek R1 推理模型', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000502, 'Qwen3 235B A22B', 'siliconflow-cn', 'Qwen/Qwen3-235B-A22B', '硅基流动 — 千问3旗舰 MoE 模型', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000503, 'Qwen3 30B A3B', 'siliconflow-cn', 'Qwen/Qwen3-30B-A3B', '硅基流动 — 千问3高性价比 MoE 模型', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000504, 'GLM-4 9B Chat', 'siliconflow-cn', 'THUDM/glm-4-9b-chat', '硅基流动 — 智谱 GLM-4 9B，免费可用', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000505, 'DeepSeek V3 Pro', 'siliconflow-cn', 'Pro/deepseek-ai/DeepSeek-V3', '硅基流动 Pro — DeepSeek V3 Pro 优先调度版', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000506, 'DeepSeek R1 Pro', 'siliconflow-cn', 'Pro/deepseek-ai/DeepSeek-R1', '硅基流动 Pro — DeepSeek R1 推理 Pro 优先调度版', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, model_type=EXCLUDED.model_type, update_time=EXCLUDED.update_time;

-- ── SiliconFlow INTL — same preset models via international endpoint ────────
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000510, 'DeepSeek V3', 'siliconflow-intl', 'deepseek-ai/DeepSeek-V3', 'SiliconFlow INTL — DeepSeek V3, strong general capability', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000511, 'DeepSeek R1', 'siliconflow-intl', 'deepseek-ai/DeepSeek-R1', 'SiliconFlow INTL — DeepSeek R1 reasoning model', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000512, 'Qwen3 235B A22B', 'siliconflow-intl', 'Qwen/Qwen3-235B-A22B', 'SiliconFlow INTL — Qwen3 flagship MoE model', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000513, 'Qwen3 30B A3B', 'siliconflow-intl', 'Qwen/Qwen3-30B-A3B', 'SiliconFlow INTL — Qwen3 efficient MoE model', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000514, 'GLM-4 9B Chat', 'siliconflow-intl', 'THUDM/glm-4-9b-chat', 'SiliconFlow INTL — Zhipu GLM-4 9B, free tier', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000515, 'DeepSeek V3 Pro', 'siliconflow-intl', 'Pro/deepseek-ai/DeepSeek-V3', 'SiliconFlow INTL Pro — DeepSeek V3 priority tier', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000516, 'DeepSeek R1 Pro', 'siliconflow-intl', 'Pro/deepseek-ai/DeepSeek-R1', 'SiliconFlow INTL Pro — DeepSeek R1 priority tier', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, model_type=EXCLUDED.model_type, update_time=EXCLUDED.update_time;

-- ── OpenCode — free public models ──────────────────────────────────────────
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000520, 'Big Pickle', 'opencode', 'big-pickle', 'OpenCode 免费模型 — Big Pickle', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000521, 'Nemotron 3 Super Free', 'opencode', 'nemotron-3-super-free', 'OpenCode 免费模型 — Nemotron 3 Super', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, update_time=EXCLUDED.update_time;
