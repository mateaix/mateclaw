-- V115: register the xAI (Grok) provider plus its Grok 3 / Grok 4 model catalog.
--
-- xAI's API is OpenAI-compatible (https://api.x.ai/v1), so the provider runs on
-- OpenAIChatModel — no new protocol or ChatModelBuilder is required;
-- OpenAiCompatibleChatModelBuilder + OpenAiCompatibleListModelsProbe handle it.
--
-- Existing seed files db/data-*.sql already carry the same rows for fresh
-- installs; this migration is the upgrade path for already-deployed databases
-- (DatabaseBootstrapRunner skips the seed when mate_user is non-empty).

-- -- Provider --------------------------------------------------------------
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
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
);

-- -- Model catalog ---------------------------------------------------------
-- IDs use the 1000000340-1000000343 block reserved for xAI so future Grok
-- additions can grow contiguously.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  (1000000340, 'Grok 4',      'xai', 'grok-4',      '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000341, 'Grok 4 Fast', 'xai', 'grok-4-fast', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000342, 'Grok 3',      'xai', 'grok-3',      '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000343, 'Grok 3 Mini', 'xai', 'grok-3-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
