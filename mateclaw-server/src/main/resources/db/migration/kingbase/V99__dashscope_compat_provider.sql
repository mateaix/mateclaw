-- V99: register a DashScope OpenAI-compatible provider entry alongside the
-- existing native dashscope provider, plus the dot-versioned Qwen families
-- (qwen3.5-*, qwen3.6-*) that only ship on compatible-mode/v1.
--
-- See the H2 copy for full background. The MySQL copy uses INSERT ... ON
-- DUPLICATE KEY UPDATE; the api_key column is intentionally omitted from the
-- update list so existing deployments that have already configured a key keep
-- it (this only matters if a future migration re-applies a similar block;
-- Flyway runs each version once today).

-- -- Provider --------------------------------------------------------------
INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES ('dashscope-compat', 'DashScope (兼容模式)', 'sk-', 'OpenAIChatModel', '', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW())
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
-- Only seed the variants that are publicly callable on compatible-mode. The
-- -max / -vl-max variants exist in the marketplace but return 404 for general
-- accounts; users with whitelist access can add them via Settings → Models.
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
  (1000000601, 'Qwen3.6 Plus', 'dashscope-compat', 'qwen3.6-plus', '通义千问 3.6 Plus 旗舰，平衡推理与速度（兼容模式专属）', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000603, 'Qwen3.5 Plus', 'dashscope-compat', 'qwen3.5-plus', '通义千问 3.5 Plus（兼容模式专属）', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000605, 'Qwen3 VL Plus', 'dashscope-compat', 'qwen3-vl-plus', '通义千问 3 视觉理解 Plus，支持图像、视频输入（兼容模式专属）', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
  model_name = EXCLUDED.model_name,
  description = EXCLUDED.description,
  builtin = EXCLUDED.builtin,
  enabled = EXCLUDED.enabled,
  update_time = EXCLUDED.update_time;
