-- Enable model discovery on the ChatGPT OAuth provider so the catalog can be
-- pulled live from chatgpt.com/backend-api/codex/models, and seed the GPT-5.5
-- flagship row alongside the existing GPT-5.4 / GPT-5.4 Mini entries. The
-- ON DUPLICATE KEY UPDATE clause keeps the migration idempotent.

UPDATE mate_model_provider
   SET support_model_discovery = TRUE,
       update_time = CURRENT_TIMESTAMP
 WHERE provider_id = 'openai-chatgpt'
   AND support_model_discovery <> TRUE;

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES (1000000252, 'GPT-5.5', 'openai-chatgpt', 'gpt-5.5', 'ChatGPT Plus/Pro flagship model', NULL, 128000, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name,
  description = EXCLUDED.description,
  update_time = NOW();
