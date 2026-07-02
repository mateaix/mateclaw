-- V153: add the GLM-5.2 flagship to the native Zhipu (BigModel / Z.AI)
-- providers. GLM-5.2 is served by the same OpenAI-compatible chat completions
-- schema as the rest of the GLM-5 line, on both the standard /api/paas/v4
-- endpoints and the /api/coding/paas/v4 subscription endpoints — so it is
-- added to all four existing Zhipu providers:
--   * zhipu-cn            (https://open.bigmodel.cn/api/paas/v4)
--   * zhipu-intl          (https://api.z.ai/api/paas/v4)
--   * zhipu-cn-codingplan (https://open.bigmodel.cn/api/coding/paas/v4)
--   * zhipu-intl-codingplan (https://api.z.ai/api/coding/paas/v4)
--
-- Coding-plan rows keep temperature 0.2 to favour deterministic code output,
-- matching the V90 catalog. Aggregator platforms (Volcano Ark, DashScope /
-- Bailian, ModelScope) do not host GLM-5.2 yet and are intentionally left
-- untouched — their hosted GLM line still tops out at glm-5 / glm-4.7.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
  (1000000214, 'GLM-5.2',        'zhipu-cn',              'glm-5.2', '最新旗舰模型',                                       0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000224, 'GLM-5.2',        'zhipu-intl',            'glm-5.2', 'Latest flagship model (International)',               0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000238, 'GLM-5.2 Coding', 'zhipu-cn-codingplan',  'glm-5.2', '智谱编码套餐 — GLM-5.2 最新旗舰',                     0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
  (1000000239, 'GLM-5.2 Coding', 'zhipu-intl-codingplan','glm-5.2', 'Zhipu Coding Plan — GLM-5.2 latest flagship (International)', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
