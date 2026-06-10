-- V115: register the xAI (Grok) provider plus its Grok 3 / Grok 4 model catalog.
--
-- See the H2 copy for full background. The MySQL copy uses INSERT ... ON
-- DUPLICATE KEY UPDATE; the api_key column is intentionally omitted from the
-- update list so existing deployments that have already configured a key keep it.

-- -- Provider --------------------------------------------------------------
INSERT INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
VALUES (
  'xai',
  'xAI (Grok)',
  'xai-',
  'OpenAIChatModel',
  '',
  'https://api.x.ai/v1',
  '{}',
  FALSE, FALSE, TRUE, TRUE, TRUE, TRUE,
  NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  api_key_prefix = VALUES(api_key_prefix),
  chat_model = VALUES(chat_model),
  base_url = VALUES(base_url),
  generate_kwargs = VALUES(generate_kwargs),
  support_model_discovery = VALUES(support_model_discovery),
  support_connection_check = VALUES(support_connection_check),
  freeze_url = VALUES(freeze_url),
  require_api_key = VALUES(require_api_key),
  update_time = VALUES(update_time);

-- -- Model catalog ---------------------------------------------------------
-- IDs use the 1000000340-1000000343 block reserved for xAI so future Grok
-- additions can grow contiguously.
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
  (1000000340, 'Grok 4',      'xai', 'grok-4',      '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000341, 'Grok 4 Fast', 'xai', 'grok-4-fast', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000342, 'Grok 3',      'xai', 'grok-3',      '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000343, 'Grok 3 Mini', 'xai', 'grok-3-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  model_name = VALUES(model_name),
  description = VALUES(description),
  builtin = VALUES(builtin),
  enabled = VALUES(enabled),
  update_time = VALUES(update_time);
