-- V153: add the GLM-5.2 flagship to the native Zhipu (BigModel / Z.AI)
-- providers. See the H2 copy for full background. Adds glm-5.2 to all four
-- existing Zhipu providers (standard + coding plan, China + International).
-- Aggregator platforms (Volcano Ark, DashScope / Bailian, ModelScope) do not
-- host GLM-5.2 yet and are intentionally left untouched.
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
  (1000000214, 'GLM-5.2', 'zhipu-cn', 'glm-5.2', '最新旗舰模型', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000224, 'GLM-5.2', 'zhipu-intl', 'glm-5.2', 'Latest flagship model (International)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000238, 'GLM-5.2 Coding', 'zhipu-cn-codingplan', 'glm-5.2', '智谱编码套餐 — GLM-5.2 最新旗舰', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000239, 'GLM-5.2 Coding', 'zhipu-intl-codingplan', 'glm-5.2', 'Zhipu Coding Plan — GLM-5.2 latest flagship (International)', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  model_name = EXCLUDED.model_name,
  description = EXCLUDED.description,
  builtin = EXCLUDED.builtin,
  enabled = EXCLUDED.enabled,
  update_time = EXCLUDED.update_time;
