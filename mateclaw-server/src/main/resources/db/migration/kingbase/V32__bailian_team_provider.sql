-- V32: Register Aliyun Bailian Token Plan provider and models
-- OpenAI-compatible endpoint for team subscription users.
-- freeze_url=1: the endpoint is plan-specific and must not be overridden.

INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES ('bailian-team', '百炼 Token Plan', 'sk-', 'OpenAIChatModel', '', 'https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', '{}', 0, 0, 0, 1, 1, 1, NOW(), NOW())
ON CONFLICT (provider_id) DO UPDATE SET name=EXCLUDED.name, api_key_prefix=EXCLUDED.api_key_prefix, chat_model=EXCLUDED.chat_model, base_url=EXCLUDED.base_url, generate_kwargs=EXCLUDED.generate_kwargs, freeze_url=EXCLUDED.freeze_url, require_api_key=EXCLUDED.require_api_key, update_time=EXCLUDED.update_time;

-- Chat models
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000400, 'Qwen 3.6 Plus',  'bailian-team', 'qwen3.6-plus',   '百炼团队套餐 — 千问旗舰推理模型，支持视觉理解与文本生成', 0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000401, 'DeepSeek V3.2',  'bailian-team', 'deepseek-v3.2',  '百炼团队套餐 — DeepSeek 最新推理模型',                   0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000402, 'GLM-5',          'bailian-team', 'glm-5',          '百炼团队套餐 — 智谱 GLM-5 文本生成模型',                 0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, model_type=EXCLUDED.model_type, update_time=EXCLUDED.update_time;

-- Image generation models (temperature/max_tokens/top_p not applicable)
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000403, 'Qwen Image 2.0',     'bailian-team', 'qwen-image-2.0',     '百炼团队套餐 — 千问图片生成模型',     NULL, NULL, NULL, 1, 1, 0, 'image', NOW(), NOW(), 0),
(1000000404, 'Qwen Image 2.0 Pro', 'bailian-team', 'qwen-image-2.0-pro', '百炼团队套餐 — 千问图片生成旗舰模型', NULL, NULL, NULL, 1, 1, 0, 'image', NOW(), NOW(), 0),
(1000000405, 'Wan 2.7 Image',      'bailian-team', 'wan2.7-image',       '百炼团队套餐 — 万相图片生成模型',     NULL, NULL, NULL, 1, 1, 0, 'image', NOW(), NOW(), 0),
(1000000406, 'Wan 2.7 Image Pro',  'bailian-team', 'wan2.7-image-pro',   '百炼团队套餐 — 万相图片生成旗舰模型', NULL, NULL, NULL, 1, 1, 0, 'image', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, model_type=EXCLUDED.model_type, update_time=EXCLUDED.update_time;
