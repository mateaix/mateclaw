-- V56: register Volcano Ark Coding Plan as a separate provider with its own
-- pre-seeded model catalog. See the H2 copy for full background.

INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES ('volcengine-plan', 'Volcano Engine Coding Plan (火山方舟代码计划)', '', 'OpenAIChatModel', '', 'https://ark.cn-beijing.volces.com/api/coding/v3', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW())
ON CONFLICT (provider_id) DO UPDATE SET name = EXCLUDED.name,
  chat_model = EXCLUDED.chat_model,
  base_url = EXCLUDED.base_url,
  generate_kwargs = EXCLUDED.generate_kwargs,
  support_model_discovery = EXCLUDED.support_model_discovery,
  support_connection_check = EXCLUDED.support_connection_check,
  freeze_url = EXCLUDED.freeze_url,
  require_api_key = EXCLUDED.require_api_key,
  update_time = EXCLUDED.update_time;

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
  (1000000320, 'Ark Coding Plan', 'volcengine-plan', 'ark-code-latest', '方舟代码计划旗舰模型，256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000321, 'Doubao Seed Code', 'volcengine-plan', 'doubao-seed-code', '豆包代码模型，256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000322, 'Doubao Seed Code Preview', 'volcengine-plan', 'doubao-seed-code-preview-251028', '豆包代码预览模型，256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000323, 'GLM 4.7 Coding', 'volcengine-plan', 'glm-4.7', 'GLM 4.7 编码版（火山方舟托管），200K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000324, 'Kimi K2 Thinking', 'volcengine-plan', 'kimi-k2-thinking', 'Kimi K2 推理版（火山方舟托管），256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000325, 'Kimi K2.5 Coding', 'volcengine-plan', 'kimi-k2.5', 'Kimi K2.5 编码版（火山方舟托管），256K 上下文', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
  model_name = EXCLUDED.model_name,
  description = EXCLUDED.description,
  builtin = EXCLUDED.builtin,
  enabled = EXCLUDED.enabled,
  update_time = EXCLUDED.update_time;
