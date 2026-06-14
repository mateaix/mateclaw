-- V93: register Xiaomi MiMo as an OpenAI-compatible provider with a
-- pre-seeded model catalog. See the H2 copy for full background.

-- -- Provider --------------------------------------------------------------
INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES (
  'xiaomi-mimo',
  'Xiaomi MiMo',
  '',
  'OpenAIChatModel',
  '',
  'https://api.xiaomimimo.com/v1',
  '{}',
  0, 0, 1, 1, 1, 1,
  NOW(), NOW()
)
ON CONFLICT (provider_id) DO UPDATE SET name = EXCLUDED.name,
  api_key_prefix = EXCLUDED.api_key_prefix,
  chat_model = EXCLUDED.chat_model,
  base_url = EXCLUDED.base_url,
  generate_kwargs = EXCLUDED.generate_kwargs,
  support_model_discovery = EXCLUDED.support_model_discovery,
  support_connection_check = EXCLUDED.support_connection_check,
  freeze_url = EXCLUDED.freeze_url,
  require_api_key = EXCLUDED.require_api_key,
  update_time = EXCLUDED.update_time;

-- -- Model catalog ---------------------------------------------------------
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
  (1000001200, 'MiMo V2.5 Pro', 'xiaomi-mimo', 'mimo-v2.5-pro', 'Xiaomi MiMo V2.5 Pro — latest flagship reasoning + coding model',         0.7, 4096, 0.8, 1, 1, 0, NOW(), NOW(), 0),
  (1000001201, 'MiMo V2.5',     'xiaomi-mimo', 'mimo-v2.5',     'Xiaomi MiMo V2.5 — balanced model in the V2.5 family',                    0.7, 4096, 0.8, 1, 1, 0, NOW(), NOW(), 0),
  (1000001202, 'MiMo V2 Pro',   'xiaomi-mimo', 'mimo-v2-pro',   'Xiaomi MiMo V2 Pro — 1M token context window flagship',                   0.7, 4096, 0.8, 1, 1, 0, NOW(), NOW(), 0),
  (1000001203, 'MiMo V2 Omni',  'xiaomi-mimo', 'mimo-v2-omni',  'Xiaomi MiMo V2 Omni — multimodal variant supporting text, vision, audio', 0.7, 4096, 0.8, 1, 1, 0, NOW(), NOW(), 0),
  (1000001204, 'MiMo V2 Flash', 'xiaomi-mimo', 'mimo-v2-flash', 'Xiaomi MiMo V2 Flash — fast, low-latency variant with 262K context',      0.7, 4096, 0.8, 1, 1, 0, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
  model_name = EXCLUDED.model_name,
  description = EXCLUDED.description,
  builtin = EXCLUDED.builtin,
  enabled = EXCLUDED.enabled,
  update_time = EXCLUDED.update_time;
