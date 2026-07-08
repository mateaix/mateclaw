-- V167: Register the Volcano Engine Agent Plan provider and its routed models.
--
-- Volcano Ark now offers two independent subscription tiers that coexist:
--   * Coding Plan  — OpenAI-compatible at /api/coding/v3 (existing
--                    'volcengine-plan' provider, left untouched here)
--   * Agent Plan   — OpenAI-compatible at /api/plan/v3 (also serves the
--                    Anthropic-compatible protocol and the Responses API at
--                    /api/plan). This migration adds it as a new provider.
-- freeze_url=TRUE: the endpoint is plan-specific and must not be overridden.
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('volcengine-agent-plan', 'Volcano Engine Agent Plan', '', 'OpenAIChatModel', '', 'https://ark.cn-beijing.volces.com/api/plan/v3', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

-- Routed models. GLM-5.2 is assigned the lowest id so it becomes the provider's
-- primary chat model: getPrimaryChatModelByProvider() falls back to the
-- earliest-id enabled chat row when the global default flag lives elsewhere.
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
KEY (id) VALUES
(1000000350, 'GLM-5.2', 'volcengine-agent-plan', 'glm-5.2', '智谱最新旗舰模型，1M 上下文，长程任务效果突出（可用 glm-latest 访问最新版）', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000351, '方舟 Agent Plan（自动路由）', 'volcengine-agent-plan', 'ark-code-latest', '自动路由入口，按需分发到最合适的套餐模型', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000353, 'Doubao-Seed-2.0-Code', 'volcengine-agent-plan', 'doubao-seed-2.0-code', 'Seed 2.0 代码强化，前端出众、多语言适配；默认 non-thinking，支持开启深度思考', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000354, 'Doubao-Seed-2.0-pro', 'volcengine-agent-plan', 'doubao-seed-2.0-pro', '旗舰级全能通用模型，适合复杂推理与长链路任务；默认开启深度思考，可关闭', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000355, 'Doubao-Seed-2.0-lite', 'volcengine-agent-plan', 'doubao-seed-2.0-lite', '兼顾生成质量与响应速度的通用生产级模型', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000356, 'Doubao-Seed-2.0-mini', 'volcengine-agent-plan', 'doubao-seed-2.0-mini', '面向低时延、高并发与成本敏感场景的轻量模型', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000357, 'Kimi-K2.7-Code', 'volcengine-agent-plan', 'kimi-k2.7-code', 'Kimi 最新 Coding 模型，长上下文指令遵循更可靠，支持文本/图片/视频输入', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000358, 'MiniMax-M3', 'volcengine-agent-plan', 'minimax-m3', '新一代 M 系列，编码与智能体评测行业顶尖', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000359, 'DeepSeek-V4-Flash', 'volcengine-agent-plan', 'deepseek-v4-flash', '更快捷经济的 DeepSeek-V4；默认开启深度思考，可手动关闭', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000360, 'DeepSeek-V4-Pro', 'volcengine-agent-plan', 'deepseek-v4-pro', 'DeepSeek-V4 Agent 能力显著增强，世界知识丰富；默认开启深度思考，可关闭', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000361, 'MiniMax-M2.7', 'volcengine-agent-plan', 'minimax-m2.7', '可自行构建复杂 Agent Harness，完成高度复杂的生产力任务', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0),
(1000000362, 'Kimi-K2.6', 'volcengine-agent-plan', 'kimi-k2.6', '月之暗面新一代智能模型；默认开启深度思考，可关闭', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, 'chat', NOW(), NOW(), 0);
