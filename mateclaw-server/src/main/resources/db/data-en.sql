-- MateClaw Seed Data - English (H2 MERGE INTO syntax, idempotent inserts)

-- Default admin (password: admin123, BCrypt encrypted)
MERGE INTO mate_user (id, username, password, nickname, role, enabled, create_time, update_time, deleted)
KEY (id)
VALUES (1, 'admin', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', 'MateClaw Admin', 'admin', TRUE, NOW(), NOW(), 0);

-- Default digital employee: General Assistant (ReAct mode)
MERGE INTO mate_agent (id, name, description, agent_type, system_prompt, model_name, max_iterations, enabled, icon, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000001, 'General Assistant', 'All-purpose helper for day-to-day questions, data analysis, and tool calling', 'react',
        'You are MateClaw''s General Assistant. You can help users answer questions, analyze data, and call tools to get things done. Please respond professionally and in a friendly manner.',
        NULL, 100, TRUE, 'pi:robot-face-happy', 'default,assistant', NOW(), NOW(), 0);

-- Default digital employee: Task Planner (Plan-Execute mode)
MERGE INTO mate_agent (id, name, description, agent_type, system_prompt, model_name, max_iterations, enabled, icon, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000002, 'Task Planner', 'Breaks complex goals into executable steps and drives them forward to completion', 'plan_execute',
        'You are a professional Task Planner. You excel at breaking complex goals into executable steps and completing them systematically.',
        NULL, 100, TRUE, 'pi:clipboard-note', 'planning,task', NOW(), NOW(), 0);

-- Default digital employee: Reasoning Analyst (explicit reasoning loops + tool calling)
MERGE INTO mate_agent (id, name, description, agent_type, system_prompt, model_name, max_iterations, enabled, icon, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000003, 'Reasoning Analyst', 'Thinks step by step with visible reasoning, ideal for problems that need thorough deliberation', 'react',
        'You are a Reasoning Analyst, an assistant that excels at deep reasoning. When facing a problem, first think through it step by step with a clear reasoning trace, then call tools or give the answer. Please respond professionally and in a friendly manner.',
        NULL, 100, TRUE, 'pi:cpu', 'react,reasoning,tools', NOW(), NOW(), 0);

-- ==================== Local Model Providers (displayed first) ====================

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('ollama', 'Ollama', '', 'OpenAIChatModel', 'ollama', 'http://127.0.0.1:11434', '{"max_tokens":null}', FALSE, TRUE, TRUE, TRUE, FALSE, FALSE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('lmstudio', 'LM Studio', '', 'OpenAIChatModel', '', 'http://localhost:1234/v1', '{"max_tokens":null}', FALSE, TRUE, TRUE, TRUE, FALSE, FALSE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('llamacpp', 'llama.cpp (Local)', '', 'OpenAIChatModel', '', '', '{}', FALSE, TRUE, FALSE, TRUE, FALSE, FALSE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('mlx', 'MLX (Local, Apple Silicon)', '', 'OpenAIChatModel', '', '', '{}', FALSE, TRUE, FALSE, TRUE, FALSE, FALSE, NOW(), NOW());

-- ==================== Cloud Model Providers ====================

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('dashscope', 'DashScope', 'sk-', 'DashScopeChatModel', '', '', '{}', FALSE, FALSE, TRUE, TRUE, FALSE, TRUE, NOW(), NOW());

-- DashScope OpenAI-compatible endpoint: shares the same sk- key as the
-- dashscope provider but routes to compatible-mode/v1. Dot-versioned qwen
-- families (qwen3.5-*, qwen3.6-*) are only callable here.
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('dashscope-compat', 'DashScope (OpenAI-compatible)', 'sk-', 'OpenAIChatModel', '', 'https://dashscope.aliyuncs.com/compatible-mode/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('modelscope', 'ModelScope', 'ms', 'OpenAIChatModel', '', 'https://api-inference.modelscope.cn/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('aliyun-codingplan', 'Aliyun Coding Plan', 'sk-sp', 'OpenAIChatModel', '', 'https://coding.dashscope.aliyuncs.com/v1', '{}', FALSE, FALSE, FALSE, FALSE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('aliyun-codingplan-intl', 'Aliyun Coding Plan (International)', 'sk-sp', 'OpenAIChatModel', '', 'https://coding-intl.dashscope.aliyuncs.com/v1', '{}', FALSE, FALSE, FALSE, FALSE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('openai', 'OpenAI', 'sk-', 'OpenAIChatModel', '', 'https://api.openai.com/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('azure-openai', 'Azure OpenAI', '', 'OpenAIChatModel', '', '', '{}', FALSE, FALSE, FALSE, TRUE, FALSE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('minimax', 'MiniMax (International)', '', 'AnthropicChatModel', '', 'https://api.minimax.io/anthropic', '{}', FALSE, FALSE, FALSE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('minimax-cn', 'MiniMax (China)', '', 'AnthropicChatModel', '', 'https://api.minimaxi.com/anthropic', '{}', FALSE, FALSE, FALSE, FALSE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('kimi-cn', 'Kimi (China)', '', 'OpenAIChatModel', '', 'https://api.moonshot.cn/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('kimi-intl', 'Kimi (International)', '', 'OpenAIChatModel', '', 'https://api.moonshot.ai/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('kimi-code', 'Kimi Code', '', 'OpenAIChatModel', '', 'https://api.kimi.com/coding/v1', '{"headers":{"User-Agent":"RooCode/1.0","HTTP-Referer":"https://github.com/RooVetGit/Roo-Cline","X-Title":"Roo Code"}}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('deepseek', 'DeepSeek', 'sk-', 'OpenAIChatModel', '', 'https://api.deepseek.com', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('anthropic', 'Anthropic', 'sk-ant-', 'AnthropicChatModel', '', 'https://api.anthropic.com', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('gemini', 'Google Gemini', '', 'GeminiChatModel', '', 'https://generativelanguage.googleapis.com', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('openrouter', 'OpenRouter', 'sk-or-', 'OpenAIChatModel', '', 'https://openrouter.ai/api/v1', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('zhipu-cn', 'Zhipu AI (China)', '', 'OpenAIChatModel', '', 'https://open.bigmodel.cn/api/paas/v4', '{"completionsPath":"/chat/completions"}', FALSE, FALSE, FALSE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('zhipu-intl', 'Zhipu AI (International)', '', 'OpenAIChatModel', '', 'https://api.z.ai/api/paas/v4', '{"completionsPath":"/chat/completions"}', FALSE, FALSE, FALSE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('volcengine', 'Volcano Engine', '', 'OpenAIChatModel', '', 'https://ark.cn-beijing.volces.com/api/v3', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('volcengine-plan', 'Volcano Engine Coding Plan', '', 'OpenAIChatModel', '', 'https://ark.cn-beijing.volces.com/api/coding/v3', '{}', FALSE, FALSE, TRUE, TRUE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('zhipu-cn-codingplan', 'Zhipu Coding Plan (BigModel)', '', 'OpenAIChatModel', '', 'https://open.bigmodel.cn/api/coding/paas/v4', '{"completionsPath":"/chat/completions"}', FALSE, FALSE, FALSE, FALSE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, create_time, update_time)
KEY (provider_id)
VALUES ('zhipu-intl-codingplan', 'Zhipu Coding Plan (Z.AI)', '', 'OpenAIChatModel', '', 'https://api.z.ai/api/coding/paas/v4', '{"completionsPath":"/chat/completions"}', FALSE, FALSE, FALSE, FALSE, TRUE, TRUE, NOW(), NOW());

MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, create_time, update_time)
KEY (provider_id)
VALUES ('openai-chatgpt', 'OpenAI ChatGPT (OAuth)', '', 'ChatGPTChatModel', '', 'https://chatgpt.com/backend-api', '{}', FALSE, FALSE, TRUE, FALSE, TRUE, FALSE, 'oauth', NOW(), NOW());

-- RFC-062: Anthropic Claude Code OAuth provider. Credentials live on local
-- disk (Keychain / ~/.claude/.credentials.json), not in this row — leave
-- api_key + oauth_access_token blank. Bearer-auth requests bypass model
-- discovery + connection check, hence both FALSE.
MERGE INTO mate_model_provider (provider_id, name, api_key_prefix, chat_model, api_key, base_url, generate_kwargs, is_custom, is_local, support_model_discovery, support_connection_check, freeze_url, require_api_key, auth_type, create_time, update_time)
KEY (provider_id)
VALUES ('anthropic-claude-code', 'Anthropic Claude Code (OAuth)', '', 'ClaudeCodeChatModel', '', 'https://api.anthropic.com', '{}', FALSE, FALSE, FALSE, FALSE, TRUE, FALSE, 'oauth', NOW(), NOW());

-- ==================== Local model pre-configs (Ollama, disabled by default) ====================
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id) VALUES
(1000000300, 'Gemma 3', 'ollama', 'gemma3:latest', 'Google Gemma 3, lightweight and efficient for local inference', 0.7, 4096, 0.8, TRUE, FALSE, FALSE, NOW(), NOW(), 0);
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id) VALUES
(1000000301, 'Qwen 3', 'ollama', 'qwen3:latest', 'Qwen 3, excellent Chinese language capabilities', 0.7, 4096, 0.8, TRUE, FALSE, FALSE, NOW(), NOW(), 0);
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id) VALUES
(1000000302, 'Llama 3.1', 'ollama', 'llama3.1:latest', 'Meta Llama 3.1, strong general-purpose model', 0.7, 4096, 0.8, TRUE, FALSE, FALSE, NOW(), NOW(), 0);
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id) VALUES
(1000000303, 'DeepSeek R1', 'ollama', 'deepseek-r1:latest', 'DeepSeek R1 reasoning model', 0.7, 4096, 0.8, TRUE, FALSE, FALSE, NOW(), NOW(), 0);
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id) VALUES
(1000000304, 'Mistral', 'ollama', 'mistral:latest', 'Mistral 7B, efficient inference', 0.7, 4096, 0.8, TRUE, FALSE, FALSE, NOW(), NOW(), 0);
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id) VALUES
(1000000305, 'Gemma 4', 'ollama', 'gemma4:latest', 'Google Gemma 4, next-gen high-performance local model', 0.7, 4096, 0.8, TRUE, FALSE, FALSE, NOW(), NOW(), 0);

-- ==================== Cloud model configurations ====================
MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES (1000000001, 'Qwen Plus', 'dashscope', 'qwen-plus', 'Default balanced model for daily Q&A and tool calling.', 0.7, 4096, 0.8, TRUE, TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES (1000000002, 'Qwen Max', 'dashscope', 'qwen-max', 'Stronger reasoning capability for complex tasks.', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);

MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES (1000000003, 'Qwen Turbo', 'dashscope', 'qwen-turbo', 'Low-latency model for high-frequency interaction.', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);

MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES (1000000004, 'Qwen Coder Plus', 'dashscope', 'qwen-coder-plus', 'Optimized for code generation and interpretation.', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0);

MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted) KEY (id) VALUES
(1000000101, 'Qwen3 Max', 'dashscope', 'qwen3-max', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000102, 'Qwen3 235B A22B Thinking', 'dashscope', 'qwen3-235b-a22b-thinking-2507', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000103, 'DeepSeek-V3.2', 'dashscope', 'deepseek-v3.2', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- Note: dotted Qwen3 versions (qwen3-plus / qwen3.5-plus / qwen3.5-max / qwen3.6-*) only ship on the
-- OpenAI-compatible endpoint. Calling them through DashScope native (text-generation/generation)
-- returns 400 InvalidParameter. They are registered under the dashscope-compat provider, which shares
-- the same sk- key but routes to compatible-mode/v1.
(1000000173, 'Qwen Long', 'dashscope', 'qwen-long', 'Long-context model with extended context support', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000174, 'Qwen Plus (latest)',  'dashscope', 'qwen-plus-latest',  'Latest stable snapshot of Qwen Plus — auto-updates as Bailian rolls new releases', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000175, 'Qwen Max (latest)',   'dashscope', 'qwen-max-latest',   'Latest stable snapshot of Qwen Max — strongest reasoning capability',              0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000176, 'Qwen Turbo (latest)', 'dashscope', 'qwen-turbo-latest', 'Latest stable snapshot of Qwen Turbo — low latency, high frequency',               0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- DashScope OpenAI-compat exclusive models (dot-versioned families) — share the same sk- key.
-- Only the -plus variants are seeded; -max / -vl-max are visible in the model market but return
-- 404 for general accounts. Users on a whitelist can add them via Settings → Models manually.
(1000000601, 'Qwen3.6 Plus',  'dashscope-compat', 'qwen3.6-plus',  'Qwen3.6 Plus flagship — balanced reasoning and speed (compat-mode only)',     0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000603, 'Qwen3.5 Plus',  'dashscope-compat', 'qwen3.5-plus',  'Qwen3.5 Plus (compat-mode only)',                                              0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000605, 'Qwen3 VL Plus', 'dashscope-compat', 'qwen3-vl-plus', 'Qwen3 vision-language Plus — accepts image / video input (compat-mode only)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000104, 'Qwen3.5-122B-A10B', 'modelscope', 'Qwen/Qwen3.5-122B-A10B', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000105, 'GLM-5', 'modelscope', 'ZhipuAI/GLM-5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000106, 'Qwen3.5 Plus', 'aliyun-codingplan', 'qwen3.5-plus', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000107, 'GLM-5', 'aliyun-codingplan', 'glm-5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000108, 'GLM-4.7', 'aliyun-codingplan', 'glm-4.7', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000109, 'MiniMax M2.5', 'aliyun-codingplan', 'MiniMax-M2.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000110, 'Kimi K2.5', 'aliyun-codingplan', 'kimi-k2.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000111, 'Qwen3 Max 2026-01-23', 'aliyun-codingplan', 'qwen3-max-2026-01-23', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000112, 'Qwen3 Coder Next', 'aliyun-codingplan', 'qwen3-coder-next', '', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000113, 'Qwen3 Coder Plus', 'aliyun-codingplan', 'qwen3-coder-plus', '', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000162, 'Qwen3.6 Plus',         'aliyun-codingplan',      'qwen3.6-plus',         'Aliyun Coding Plan — Qwen3.6 Plus flagship',                  0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000241, 'Qwen3.6 Plus',         'aliyun-codingplan-intl', 'qwen3.6-plus',         'Aliyun Coding Plan (Intl) — Qwen3.6 Plus flagship',           0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000242, 'Qwen3.5 Plus',         'aliyun-codingplan-intl', 'qwen3.5-plus',         'Aliyun Coding Plan (Intl) — Qwen3.5 balanced',                0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000243, 'GLM-5',                'aliyun-codingplan-intl', 'glm-5',                'Aliyun Coding Plan (Intl) — GLM-5 hosted on DashScope',       0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000244, 'GLM-4.7',              'aliyun-codingplan-intl', 'glm-4.7',              'Aliyun Coding Plan (Intl) — GLM-4.7 hosted on DashScope',     0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000245, 'MiniMax M2.5',         'aliyun-codingplan-intl', 'MiniMax-M2.5',         'Aliyun Coding Plan (Intl) — MiniMax M2.5 hosted on DashScope', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000246, 'Kimi K2.5',            'aliyun-codingplan-intl', 'kimi-k2.5',            'Aliyun Coding Plan (Intl) — Kimi K2.5 hosted on DashScope',   0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000247, 'Qwen3 Max 2026-01-23', 'aliyun-codingplan-intl', 'qwen3-max-2026-01-23', 'Aliyun Coding Plan (Intl) — Qwen3 Max pinned snapshot',       0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000248, 'Qwen3 Coder Next',     'aliyun-codingplan-intl', 'qwen3-coder-next',     'Aliyun Coding Plan (Intl) — Qwen3 Coder Next, agentic coding', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000249, 'Qwen3 Coder Plus',     'aliyun-codingplan-intl', 'qwen3-coder-plus',     'Aliyun Coding Plan (Intl) — Qwen3 Coder Plus, agentic coding', 0.2, 8192, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000114, 'GPT-5.2', 'openai', 'gpt-5.2', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000115, 'GPT-5', 'openai', 'gpt-5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000116, 'GPT-5 Mini', 'openai', 'gpt-5-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000117, 'GPT-5 Nano', 'openai', 'gpt-5-nano', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000118, 'GPT-4.1', 'openai', 'gpt-4.1', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000119, 'GPT-4.1 Mini', 'openai', 'gpt-4.1-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000120, 'GPT-4.1 Nano', 'openai', 'gpt-4.1-nano', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000121, 'o3', 'openai', 'o3', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000122, 'o4-mini', 'openai', 'o4-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000123, 'GPT-4o', 'openai', 'gpt-4o', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000124, 'GPT-4o Mini', 'openai', 'gpt-4o-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000125, 'GPT-5 Chat', 'azure-openai', 'gpt-5-chat', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000126, 'GPT-5 Mini', 'azure-openai', 'gpt-5-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000127, 'GPT-5 Nano', 'azure-openai', 'gpt-5-nano', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000128, 'GPT-4.1', 'azure-openai', 'gpt-4.1', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000129, 'GPT-4.1 Mini', 'azure-openai', 'gpt-4.1-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000130, 'GPT-4.1 Nano', 'azure-openai', 'gpt-4.1-nano', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000131, 'GPT-4o', 'azure-openai', 'gpt-4o', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000132, 'GPT-4o Mini', 'azure-openai', 'gpt-4o-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000133, 'MiniMax M2.5', 'minimax', 'MiniMax-M2.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000134, 'MiniMax M2.5 Highspeed', 'minimax', 'MiniMax-M2.5-highspeed', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000135, 'MiniMax M2.7', 'minimax', 'MiniMax-M2.7', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000136, 'MiniMax M2.7 Highspeed', 'minimax', 'MiniMax-M2.7-highspeed', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000137, 'MiniMax M2.5', 'minimax-cn', 'MiniMax-M2.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000138, 'MiniMax M2.5 Highspeed', 'minimax-cn', 'MiniMax-M2.5-highspeed', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000139, 'MiniMax M2.7', 'minimax-cn', 'MiniMax-M2.7', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000140, 'MiniMax M2.7 Highspeed', 'minimax-cn', 'MiniMax-M2.7-highspeed', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000141, 'Kimi K2.5', 'kimi-cn', 'kimi-k2.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000142, 'Kimi K2 0905 Preview', 'kimi-cn', 'kimi-k2-0905-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000143, 'Kimi K2 0711 Preview', 'kimi-cn', 'kimi-k2-0711-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000144, 'Kimi K2 Turbo Preview', 'kimi-cn', 'kimi-k2-turbo-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000145, 'Kimi K2 Thinking', 'kimi-cn', 'kimi-k2-thinking', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000146, 'Kimi K2 Thinking Turbo', 'kimi-cn', 'kimi-k2-thinking-turbo', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000147, 'Kimi K2.5', 'kimi-intl', 'kimi-k2.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000148, 'Kimi K2 0905 Preview', 'kimi-intl', 'kimi-k2-0905-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000149, 'Kimi K2 0711 Preview', 'kimi-intl', 'kimi-k2-0711-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000150, 'Kimi K2 Turbo Preview', 'kimi-intl', 'kimi-k2-turbo-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000151, 'Kimi K2 Thinking', 'kimi-intl', 'kimi-k2-thinking', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000152, 'Kimi K2 Thinking Turbo', 'kimi-intl', 'kimi-k2-thinking-turbo', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000153, 'DeepSeek Chat', 'deepseek', 'deepseek-chat', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000154, 'DeepSeek Reasoner', 'deepseek', 'deepseek-reasoner', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- DeepSeek V4 (1M context, native thinking via DeepSeekV4ThinkingDecorator)
(1000000282, 'DeepSeek V4 Flash', 'deepseek', 'deepseek-v4-flash', 'DeepSeek V4 Flash (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000283, 'DeepSeek V4 Pro', 'deepseek', 'deepseek-v4-pro', 'DeepSeek V4 Pro (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000155, 'Gemini 3.1 Pro Preview', 'gemini', 'gemini-3.1-pro-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000156, 'Gemini 3 Flash Preview', 'gemini', 'gemini-3-flash-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000157, 'Gemini 3.1 Flash Lite Preview', 'gemini', 'gemini-3.1-flash-lite-preview', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000158, 'Gemini 2.5 Pro', 'gemini', 'gemini-2.5-pro', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000159, 'Gemini 2.5 Flash', 'gemini', 'gemini-2.5-flash', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000160, 'Gemini 2.5 Flash Lite', 'gemini', 'gemini-2.5-flash-lite', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000161, 'Gemini 2.0 Flash', 'gemini', 'gemini-2.0-flash', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000200, 'GPT-5', 'openrouter', 'openai/gpt-5', 'GPT-5 via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000201, 'Claude Opus 4.6', 'openrouter', 'anthropic/claude-opus-4-6', 'Claude Opus 4.6 via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000202, 'Claude Sonnet 4.6', 'openrouter', 'anthropic/claude-sonnet-4-6', 'Claude Sonnet 4.6 via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000203, 'Gemini 2.5 Pro', 'openrouter', 'google/gemini-2.5-pro', 'Gemini 2.5 Pro via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000204, 'Llama 4 Maverick', 'openrouter', 'meta-llama/llama-4-maverick', 'Llama 4 Maverick via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000205, 'DeepSeek R1', 'openrouter', 'deepseek/deepseek-r1', 'DeepSeek R1 via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000206, 'Qwen3.6 Plus (free)', 'openrouter', 'qwen/qwen3.6-plus:free', 'Free Qwen3.6 Plus via OpenRouter (vision)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000207, 'Gemini 2.5 Flash (free)', 'openrouter', 'google/gemini-2.5-flash:free', 'Free Gemini 2.5 Flash via OpenRouter (vision)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000208, 'Llama 4 Maverick (free)', 'openrouter', 'meta-llama/llama-4-maverick:free', 'Free Llama 4 Maverick via OpenRouter (vision)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000210, 'GLM-5-Turbo', 'zhipu-cn', 'glm-5-turbo', 'Fast inference model (recommended)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000211, 'GLM-5V-Turbo', 'zhipu-cn', 'glm-5v-turbo', 'Multimodal vision model (recommended)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000212, 'GLM-5', 'zhipu-cn', 'glm-5', 'Flagship model', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000213, 'GLM-5.1', 'zhipu-cn', 'glm-5.1', 'Latest flagship model', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000220, 'GLM-5-Turbo', 'zhipu-intl', 'glm-5-turbo', 'Fast inference model (International, recommended)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000221, 'GLM-5V-Turbo', 'zhipu-intl', 'glm-5v-turbo', 'Multimodal vision model (International, recommended)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000222, 'GLM-5', 'zhipu-intl', 'glm-5', 'Flagship model (International)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000223, 'GLM-5.1', 'zhipu-intl', 'glm-5.1', 'Latest flagship model (International)', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000230, 'GLM-5 Coding',       'zhipu-cn-codingplan',   'glm-5',       'Zhipu Coding Plan — GLM-5 flagship',                    0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000231, 'GLM-5.1 Coding',     'zhipu-cn-codingplan',   'glm-5.1',     'Zhipu Coding Plan — GLM-5.1 latest flagship',           0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000232, 'GLM-5-Turbo Coding', 'zhipu-cn-codingplan',   'glm-5-turbo', 'Zhipu Coding Plan — GLM-5 fast variant',                0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000233, 'GLM-4.7 Coding',     'zhipu-cn-codingplan',   'glm-4.7',     'Zhipu Coding Plan — GLM-4.7',                           0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000234, 'GLM-5 Coding',       'zhipu-intl-codingplan', 'glm-5',       'Zhipu Coding Plan — GLM-5 flagship (International)',    0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000235, 'GLM-5.1 Coding',     'zhipu-intl-codingplan', 'glm-5.1',     'Zhipu Coding Plan — GLM-5.1 flagship (International)',  0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000236, 'GLM-5-Turbo Coding', 'zhipu-intl-codingplan', 'glm-5-turbo', 'Zhipu Coding Plan — GLM-5 fast (International)',        0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000237, 'GLM-4.7 Coding',     'zhipu-intl-codingplan', 'glm-4.7',     'Zhipu Coding Plan — GLM-4.7 (International)',           0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000310, 'Doubao Seed 1.8', 'volcengine', 'doubao-seed-1-8-251228', 'Doubao flagship multimodal model, text + image, 256K context', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000311, 'Doubao Seed Code Preview', 'volcengine', 'doubao-seed-code-preview-251028', 'Doubao code preview model, text + image, 256K context', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000312, 'Kimi K2.5', 'volcengine', 'kimi-k2-5-260127', 'Kimi K2.5 (hosted on Volcano Ark), text + image, 256K context', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000313, 'GLM 4.7', 'volcengine', 'glm-4-7-251222', 'GLM 4.7 (hosted on Volcano Ark), text + image, 200K context', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000314, 'DeepSeek V3.2', 'volcengine', 'deepseek-v3-2-251201', 'DeepSeek V3.2 (hosted on Volcano Ark), text + image, 128K context', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000320, 'Ark Coding Plan', 'volcengine-plan', 'ark-code-latest', 'Ark Coding Plan flagship model, 256K context', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000321, 'Doubao Seed Code', 'volcengine-plan', 'doubao-seed-code', 'Doubao code model, 256K context', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000322, 'Doubao Seed Code Preview', 'volcengine-plan', 'doubao-seed-code-preview-251028', 'Doubao code preview model, 256K context', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000323, 'GLM 4.7 Coding', 'volcengine-plan', 'glm-4.7', 'GLM 4.7 coding edition (hosted on Volcano Ark), 200K context', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000324, 'Kimi K2 Thinking', 'volcengine-plan', 'kimi-k2-thinking', 'Kimi K2 Thinking (hosted on Volcano Ark), 256K context', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000325, 'Kimi K2.5 Coding', 'volcengine-plan', 'kimi-k2.5', 'Kimi K2.5 coding edition (hosted on Volcano Ark), 256K context', 0.2, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000240, 'Kimi for Coding', 'kimi-code', 'kimi-for-coding', 'Kimi Code dedicated coding model', 0.2, 32768, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000250, 'GPT-5.4', 'openai-chatgpt', 'gpt-5.4', 'ChatGPT Plus/Pro member model (OAuth login)', NULL, 128000, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000251, 'GPT-5.4 Mini', 'openai-chatgpt', 'gpt-5.4-mini', 'ChatGPT member lightweight model', NULL, 128000, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000252, 'GPT-5.5', 'openai-chatgpt', 'gpt-5.5', 'ChatGPT Plus/Pro flagship model', NULL, 128000, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- GPT-5.5 series (direct OpenAI / Azure / OpenRouter / ChatGPT)
(1000000260, 'GPT-5.5', 'openai', 'gpt-5.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000261, 'GPT-5.5 Mini', 'openai', 'gpt-5.5-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000262, 'GPT-5.5 Nano', 'openai', 'gpt-5.5-nano', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000263, 'GPT-5.5', 'azure-openai', 'gpt-5.5', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000264, 'GPT-5.5 Mini', 'azure-openai', 'gpt-5.5-mini', '', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000265, 'GPT-5.5', 'openrouter', 'openai/gpt-5.5', 'GPT-5.5 via OpenRouter', 0.7, 4096, 0.8, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- Claude 4.7 series (direct Anthropic + OpenRouter). Sonnet/Opus.
-- Note: Claude 4.7 forbids temperature/top_p/top_k — handled in AgentAnthropicChatModelBuilder.
(1000000270, 'Claude Opus 4.7', 'anthropic', 'claude-opus-4-7', 'Anthropic Claude Opus 4.7 (xhigh adaptive thinking)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- Anthropic only released Opus 4.7 — Sonnet stays at 4.6 until further notice.
(1000000271, 'Claude Sonnet 4.6', 'anthropic', 'claude-sonnet-4-6', 'Anthropic Claude Sonnet 4.6 (latest Sonnet — 4.7 not yet released)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000272, 'Claude Opus 4.7', 'openrouter', 'anthropic/claude-opus-4-7', 'Claude Opus 4.7 via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000273, 'Claude Sonnet 4.6', 'openrouter', 'anthropic/claude-sonnet-4-6', 'Claude Sonnet 4.6 via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- RFC-062: Claude 4.7 via Claude Code OAuth subscription (Pro/Max plan).
(1000000280, 'Claude Opus 4.7', 'anthropic-claude-code', 'claude-opus-4-7', 'Claude Opus 4.7 via Claude Code Pro/Max subscription', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000281, 'Claude Sonnet 4.6', 'anthropic-claude-code', 'claude-sonnet-4-6', 'Claude Sonnet 4.6 via Claude Code Pro/Max subscription', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0);

-- Default system settings
MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000001, 'language', 'en-US', 'Current UI language', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000002, 'streamEnabled', 'true', 'Enable streaming response', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000003, 'debugMode', 'false', 'Enable debug mode', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000004, 'stateGraphEnabled', 'true', 'Enable StateGraph-based ReAct Agent', NOW(), NOW());

-- Search service configuration
MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000005, 'searchEnabled', 'true', 'Enable web search', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000006, 'searchProvider', 'serper', 'Search provider', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000007, 'searchFallbackEnabled', 'false', 'Fallback to alternative provider on failure', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000008, 'serperApiKey', '', 'Serper API Key', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000009, 'serperBaseUrl', 'https://google.serper.dev/search', 'Serper base URL', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000010, 'tavilyApiKey', '', 'Tavily API Key', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000011, 'tavilyBaseUrl', 'https://api.tavily.com/search', 'Tavily base URL', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000012, 'duckduckgoEnabled', 'true', 'DuckDuckGo keyless search fallback (zero-config)', NOW(), NOW());

MERGE INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
KEY (id)
VALUES (1000000013, 'searxngBaseUrl', '', 'SearXNG instance base URL (auto-configured in Docker)', NOW(), NOW());

-- Speech-to-text (STT) defaults — enabled out of the box so users only need to configure an API key.
-- Skip-if-exists on setting_key (NOT MERGE BY id) so we don't trip the
-- UNIQUE index when an existing user has the row at a runtime-assigned id
-- from toggling the UI before this seed shipped. V46 covers the same.
INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000020, 'sttEnabled', 'true', 'Enable speech-to-text (TalkMode mic input)', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttEnabled');

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000021, 'sttProvider', 'auto', 'STT provider: auto / openai / dashscope', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttProvider');

INSERT INTO mate_system_setting (id, setting_key, setting_value, description, create_time, update_time)
SELECT 1000000022, 'sttFallbackEnabled', 'true', 'Try alternate STT provider when the primary fails', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM mate_system_setting WHERE setting_key = 'sttFallbackEnabled');

-- Built-in tool: Date & Time
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000001, 'DateTimeTool', 'Date & Time', 'Get current date and time information', 'builtin', 'dateTimeTool', '🕐', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Web Search
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000002, 'WebSearchTool', 'Web Search', 'Search the internet for real-time information', 'builtin', 'webSearchTool', '🔍', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Shell Execute (enabled by default, dangerous ops controlled by ToolGuard)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000003, 'ShellExecuteTool', 'Shell Execute', 'Execute shell commands on the local server. Used for system commands, viewing files, running scripts. Dangerous operations trigger approval.', 'builtin', 'shellExecuteTool', '🖥', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Read File
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000004, 'ReadFileTool', 'Read File', 'Read file contents with line range support and auto-truncation for large output.', 'builtin', 'readFileTool', '📖', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Write File (enabled by default, dangerous ops controlled by ToolGuard)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000005, 'WriteFileTool', 'Write File', 'Write content to a file. Overwrites if exists, creates if not. Requires user approval.', 'builtin', 'writeFileTool', '📝', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Edit File (enabled by default, dangerous ops controlled by ToolGuard)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000006, 'EditFileTool', 'Edit File', 'Edit file content via find-and-replace. Matches old_text exactly and replaces with new_text. Requires user approval.', 'builtin', 'editFileTool', '✏️', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Skill File Reader (Skill Runtime Tool)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000007, 'SkillFileTool', 'Skill File Reader', 'Read files within skill packages (SKILL.md/references/scripts) and list skill file directory tree.', 'builtin', 'skillFileTool', '📖', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Skill Script Runner (Skill Runtime Tool)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000008, 'SkillScriptTool', 'Skill Script Runner', 'Execute scripts in skill package scripts/ directory (Python/Bash/Node), strictly sandboxed.', 'builtin', 'skillScriptTool', '⚡', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: File Type Detector
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000009, 'FileTypeDetectorTool', 'File Type Detector', 'Detect file MIME type and category to help choose the appropriate reading tool.', 'builtin', 'fileTypeDetectorTool', '🔍', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Document Extractor
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000010, 'DocumentExtractTool', 'Document Extractor', 'Extract text from PDF, Word, Excel, PowerPoint documents with fallback chain.', 'builtin', 'documentExtractTool', '📄', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Workspace Memory
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000011, 'WorkspaceMemoryTool', 'Workspace Memory', 'Read/write workspace Markdown documents for persistent memory (PROFILE.md, MEMORY.md, etc.).', 'builtin', 'workspaceMemoryTool', '🧠', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Browser Control (Playwright)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000012, 'BrowserUseTool', 'Browser Control', 'Launch and control browser for web automation: navigate, screenshot, click, type, execute JS.', 'builtin', 'browserUseTool', '🌐', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: MateClaw Docs
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000013, 'MateClawDocTool', 'MateClaw Docs', 'Read built-in MateClaw project documentation. action=list to list docs, action=read to read specific doc.', 'builtin', 'mateClawDocTool', '📚', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Agent Delegation (Multi-Agent Collaboration)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000014, 'DelegateAgentTool', 'Agent Delegation', 'Delegate tasks to other Agents for multi-agent collaboration. Call target Agent by name, run in isolated session and return result.', 'builtin', 'delegateAgentTool', '🤝', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Video Generation
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000015, 'VideoGenerateTool', 'Video Generation', 'Generate videos using AI. Supports text-to-video and image-to-video modes. Video generation is asynchronous and will appear in conversation when complete.', 'builtin', 'videoGenerateTool', '🎬', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000016, 'ImageGenerateTool', 'Image Generation', 'Generate images using AI. Supports text-to-image mode with multiple providers: DashScope, OpenAI DALL-E, fal.ai Flux, Zhipu CogView. Auto-fallback between providers.', 'builtin', 'imageGenerateTool', '🎨', TRUE, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000017, 'WikiTool', 'Wiki Knowledge Base', 'Read, search, and trace sources in Wiki knowledge bases. Supports wiki_read_page, wiki_list_pages, wiki_search_pages, wiki_trace_source.', 'builtin', 'wikiTool', '📚', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: Cron Job Management (chat-based scheduled task management)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000018, 'CronJobTool', 'Scheduled Tasks', 'Create, list, enable/disable, and delete scheduled tasks (cron jobs) through chat. Supports 5-field cron expressions for flexible scheduling.', 'builtin', 'cronJobTool', '⏰', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: DOCX Render (RFC-045 — in-process Apache POI, millisecond .docx creation)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000019, 'DocxRenderTool', 'DOCX Render', 'Render Markdown directly into a .docx and return a one-time download link. In-process Apache POI implementation, no Node.js subprocess; supports headings, bold, lists, tables. Preferred tool for creating new documents.', 'builtin', 'docxRenderTool', '📝', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: XLSX Render (in-process Apache POI; markdown tables -> multi-sheet workbook)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000020, 'XlsxRenderTool', 'XLSX Render', 'Render Markdown directly into a .xlsx workbook and return a one-time download link. In-process Apache POI; each # heading becomes a sheet, pipe tables become rows, numeric cells auto-detected.', 'builtin', 'xlsxRenderTool', '📊', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: PPTX Render (in-process Apache POI; Marp-style markdown -> .pptx deck)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000021, 'PptxRenderTool', 'PPTX Render', 'Render Marp-style Markdown directly into a .pptx deck and return a one-time download link. In-process Apache POI; --- separates slides, # / ## titles, - bullets, <!-- speaker notes -->.', 'builtin', 'pptxRenderTool', '🎞️', TRUE, TRUE, NOW(), NOW(), 0);

-- Built-in tool: PDF Render (dual backend: LibreOffice subprocess preferred, OpenPDF + Flying Saucer fallback)
MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000022, 'PdfRenderTool', 'PDF Render', 'Render Markdown into a final-form .pdf and return a one-time download link. Two backends (LibreOffice subprocess preferred, OpenPDF + Flying Saucer fallback); supports YAML frontmatter for cover / page header / page footer.', 'builtin', 'pdfRenderTool', '📄', TRUE, TRUE, NOW(), NOW(), 0);

-- Example MCP Server: Filesystem (see MateClaw docs mcpServers.filesystem)
MERGE INTO mate_mcp_server (
    id, name, description, transport, url, headers_json, command, args_json, env_json, cwd,
    enabled, connect_timeout_seconds, read_timeout_seconds, last_status, last_error,
    last_connected_time, tool_count, builtin, create_time, update_time, deleted
)
KEY (id)
VALUES (
    1000000901,
    'filesystem',
    'Filesystem MCP for MateClaw workspace',
    'stdio',
    NULL,
    NULL,
    'npx',
    '["-y","@modelcontextprotocol/server-filesystem","${user.home}"]',
    '{}',
    NULL,
    FALSE,
    30,
    30,
    'disconnected',
    NULL,
    NULL,
    0,
    FALSE,
    NOW(),
    NOW(),
    0
);

-- Pre-configured MCP Server: GitHub (enable after setting GITHUB_TOKEN env var)
MERGE INTO mate_mcp_server (
    id, name, description, transport, url, headers_json, command, args_json, env_json, cwd,
    enabled, connect_timeout_seconds, read_timeout_seconds, last_status, last_error,
    last_connected_time, tool_count, builtin, create_time, update_time, deleted
)
KEY (id)
VALUES (
    1000000902,
    'github',
    'GitHub MCP Server — Search repos/code/issues, manage PRs and files',
    'stdio',
    NULL,
    NULL,
    'npx',
    '["-y","@modelcontextprotocol/server-github"]',
    '{"GITHUB_PERSONAL_ACCESS_TOKEN":""}',
    NULL,
    FALSE,
    30,
    30,
    'disconnected',
    NULL,
    NULL,
    0,
    FALSE,
    NOW(),
    NOW(),
    0
);

-- Built-in skills: skill metadata
-- DEPRECATED (RFC-044 §4.2): The authoritative source for builtin skills is now
-- classpath:skills/<name>/SKILL.md, upserted on startup by BuiltinSkillSeedService.
-- These MERGE blocks remain as a one-version compatibility shim and will be
-- removed in the next release. New skills should NOT be added here — drop a
-- SKILL.md under skills/<name>/ and the seed service will register it.
MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000001, 'cron', 'Cron job management. Create, query, pause, resume, delete tasks via commands or console. Execute on schedule and send results to channels.', 'builtin', '⏰', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'cron,schedule,automation', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000002, 'file_reader', 'Read and summarize text files such as txt, md, json, csv, log, and code files. PDF and Office files are handled by dedicated skills.', 'builtin', '📄', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'file,reader,text,summary', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000003, 'dingtalk_channel_connect', 'Assist with DingTalk channel setup, supporting visible browser, login pause, and pre-publish checks.', 'builtin', '🤖', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'dingtalk,channel,browser,automation', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000004, 'himalaya', 'Manage emails via CLI with multi-account IMAP/SMTP, search, read, reply, and attachment handling.', 'builtin', '📧', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md","homepage":"https://github.com/pimalaya/himalaya"}', TRUE, TRUE, 'email,imap,smtp,cli', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000005, 'news', 'Query latest news from the internet. Supports politics, finance, society, international, tech, sports, entertainment categories. Auto-adapts to built-in and tool search.', 'builtin', '📰', '2.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'news,web,search,summary', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000006, 'pdf', 'PDF operations: read, extract text and tables, merge/split, rotate, watermark, fill forms, encrypt/decrypt, OCR. Includes scripts for form field extraction, filling, bounding box validation, and PDF-to-image conversion.', 'builtin', '📕', '1.0.0', 'Anthropic Skills', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'pdf,ocr,forms,document', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000007, 'docx', 'Create, read, and edit Word documents with TOC, headers/footers, tables, images, revisions and comments. Includes scripts for XML unpack/pack, schema validation, tracked changes, and LibreOffice integration.', 'builtin', '📝', '1.0.0', 'Anthropic Skills', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'docx,word,document,office', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000008, 'pptx', 'Create, read, and edit PowerPoint presentations with templates, layouts, notes and comments. Includes scripts for slide manipulation, thumbnail generation, XML validation, and LibreOffice integration.', 'builtin', '📊', '1.0.0', 'Anthropic Skills', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'pptx,presentation,slides,office', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000009, 'xlsx', 'Read, edit, create and format spreadsheets with formula support, data cleaning and analysis. Includes scripts for formula recalculation, XML unpack/pack, schema validation, and LibreOffice integration.', 'builtin', '📈', '1.0.0', 'Anthropic Skills', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'xlsx,excel,csv,spreadsheet,data', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000010, 'browser_visible', 'Launch a visible browser window for demos, debugging, or scenarios requiring human interaction.', 'builtin', '🖥️', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'browser,visible,headed,automation', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000012, 'browser_cdp', 'Connect or launch Chrome via CDP for remote debugging, browser sharing, or external tool collaboration.', 'builtin', '🔌', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'browser,cdp,chrome,debugging,automation', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000011, 'guidance', 'Answer user questions about MateClaw installation and configuration by reading local docs first.', 'builtin', '🧭', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'docs,guidance,configuration,qa', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000013, 'mateclaw_source_index', 'Map user questions to MateClaw doc paths and source code entry points to reduce blind searching.', 'builtin', '🗂️', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'docs,index,source,qa', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000014, 'sql_query', 'Query databases using natural language. Discover schemas, generate SQL, and execute read-only queries against configured external datasources.', 'builtin', '📊', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'sql,database,query,data', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000015, 'steve_jobs_perspective', 'Steve Jobs thinking OS. Analyze products, evaluate decisions, and give feedback through Jobs'' perspective, using his six mental models and distinctive expression style.', 'builtin', '🍎', '1.0.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'persona,jobs,product,strategy,thinking', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000016, 'make_plan', 'When a task requires multi-step breakdown or uncertain execution path, request a step-by-step actionable plan from a stronger Agent, then execute it yourself.', 'builtin', '🗺️', '1.3.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'plan,delegate,agent,collaboration', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000017, 'chat_with_agent', 'When you need to consult another Agent, seek help, or the user explicitly requests an Agent to participate, use this skill for single or parallel delegation.', 'builtin', '💬', '1.2.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'agent,chat,collaborate,delegate', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000018, 'channel_message', 'Use when you need to proactively push one-way messages to users, sessions, or channels. For task completion notifications, scheduled reminders, and async result delivery.', 'builtin', '📤', '1.3.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'channel,message,push,notify,dingtalk,feishu', NOW(), NOW(), 0);

MERGE INTO mate_skill (id, name, description, skill_type, icon, version, author, config_json, enabled, builtin, tags, create_time, update_time, deleted)
KEY (id)
VALUES (1000000019, 'multi_agent_collaboration', 'When a task requires the professional capabilities of multiple Agents, orchestrate parallel or serial multi-agent collaboration and integrate results.', 'builtin', '🤝', '1.4.0', 'MateClaw', '{"upstream":"mateclaw","entryFile":"SKILL.md"}', TRUE, TRUE, 'multi-agent,collaboration,orchestration,parallel', NOW(), NOW(), 0);

-- RFC-042 §2.2 — bilingual display names for the 19 builtin skills.
-- Identical across all four data-*.sql files because name_zh / name_en are
-- permanent attributes, not locale-conditional. The UI picks which one to
-- show based on the active i18n locale and falls back to `name` when null.
UPDATE mate_skill SET name_zh = '定时任务',       name_en = 'Cron Jobs'                WHERE name = 'cron';
UPDATE mate_skill SET name_zh = '文件阅读器',     name_en = 'File Reader'              WHERE name = 'file_reader';
UPDATE mate_skill SET name_zh = '钉钉渠道接入',   name_en = 'DingTalk Channel'         WHERE name = 'dingtalk_channel_connect';
UPDATE mate_skill SET name_zh = '邮件管理',       name_en = 'Email (Himalaya)'         WHERE name = 'himalaya';
UPDATE mate_skill SET name_zh = '新闻查询',       name_en = 'News'                     WHERE name = 'news';
UPDATE mate_skill SET name_zh = 'PDF 处理',       name_en = 'PDF'                      WHERE name = 'pdf';
UPDATE mate_skill SET name_zh = 'Word 文档',      name_en = 'Word Document'            WHERE name = 'docx';
UPDATE mate_skill SET name_zh = 'PPT 演示',       name_en = 'PowerPoint'               WHERE name = 'pptx';
UPDATE mate_skill SET name_zh = 'Excel 表格',     name_en = 'Excel'                    WHERE name = 'xlsx';
UPDATE mate_skill SET name_zh = '可见浏览器',     name_en = 'Visible Browser'          WHERE name = 'browser_visible';
UPDATE mate_skill SET name_zh = '浏览器 CDP',     name_en = 'Browser CDP'              WHERE name = 'browser_cdp';
UPDATE mate_skill SET name_zh = '安装指引',       name_en = 'Setup Guidance'           WHERE name = 'guidance';
UPDATE mate_skill SET name_zh = '源码索引',       name_en = 'Source Index'             WHERE name = 'mateclaw_source_index';
UPDATE mate_skill SET name_zh = 'SQL 查询',       name_en = 'SQL Query'                WHERE name = 'sql_query';
UPDATE mate_skill SET name_zh = '乔布斯视角',     name_en = 'Steve Jobs Perspective'   WHERE name = 'steve_jobs_perspective';
UPDATE mate_skill SET name_zh = '制定计划',       name_en = 'Make Plan'                WHERE name = 'make_plan';
UPDATE mate_skill SET name_zh = '咨询智能体',     name_en = 'Chat with Agent'          WHERE name = 'chat_with_agent';
UPDATE mate_skill SET name_zh = '渠道推送',       name_en = 'Channel Push'             WHERE name = 'channel_message';
UPDATE mate_skill SET name_zh = '多智能体协作',   name_en = 'Multi-Agent Collaboration' WHERE name = 'multi_agent_collaboration';

-- Populate skill_content for key built-in skills (SKILL.md execution protocol)
-- NOTE: For pdf/docx/pptx/xlsx/himalaya, the authoritative SKILL.md is bundled in
-- classpath:skills/{name}/ and auto-synced to workspace on startup.
-- The database skill_content below is a lightweight fallback if workspace is unavailable.
UPDATE mate_skill SET skill_content = '# PDF Processing Guide

## Capabilities
- Read PDF: extract text using extract_pdf_text or extract_document_text
- Extract tables and metadata
- Merge/split PDF (via skill scripts)
- Rotate pages, add watermarks
- Fill PDF forms (via scripts/fill_fillable_fields.py, scripts/fill_pdf_form_with_annotations.py)
- Encrypt/decrypt PDF
- OCR scanned documents

## Available Scripts (in skill workspace)
- `scripts/check_fillable_fields.py` - detect fillable form fields
- `scripts/extract_form_field_info.py` - extract form field metadata
- `scripts/extract_form_structure.py` - analyze non-fillable PDF structure
- `scripts/fill_fillable_fields.py` - fill form fields
- `scripts/fill_pdf_form_with_annotations.py` - fill with annotations
- `scripts/check_bounding_boxes.py` - validate form bounding boxes
- `scripts/convert_pdf_to_images.py` - convert PDF pages to images
- `scripts/create_validation_image.py` - create overlay validation images

## Correct Usage

### Extract PDF text (recommended)
```tool
extract_pdf_text(filePath="/path/to/document.pdf")
```

### Specify page range
```tool
extract_pdf_text(filePath="/path/to/document.pdf", pages="1-5")
```

## Important
- NEVER use read_file on PDF - returns binary garbage
- Always use extract_pdf_text or extract_document_text
- Use run_skill_script to execute scripts in the scripts/ directory

## Extraction strategy (auto fallback)
1. pdftotext (poppler-utils) - best quality
2. Python pdfplumber/pypdf
3. Java PDF parser - pure Java, no external dependencies

The result shows which method was used.' WHERE id = 1000000006;

UPDATE mate_skill SET skill_content = '# Word Document Processing

## Capabilities
- Read and extract Word content: use extract_docx_text or extract_document_text
- Create new Word documents (.docx) with docx-js (Node.js)
- Edit existing documents: unpack XML -> edit -> repack with validation
- Handle tracked changes, comments, images
- Support TOC generation, headers/footers

## Available Scripts (in skill workspace)
- `scripts/office/unpack.py` - extract and pretty-print DOCX XML
- `scripts/office/pack.py` - repack with validation and auto-repair
- `scripts/office/validate.py` - validate against XSD schemas
- `scripts/office/soffice.py` - LibreOffice CLI wrapper
- `scripts/comment.py` - add comments to documents
- `scripts/accept_changes.py` - accept all tracked changes

## Correct Usage

### Extract Word text (recommended)
```tool
extract_docx_text(filePath="/path/to/document.docx")
```

## Editing Workflow
1. Unpack: `python scripts/office/unpack.py document.docx unpacked/`
2. Edit XML in unpacked/word/
3. Pack: `python scripts/office/pack.py unpacked/ output.docx --original document.docx`

## Important
- NEVER use read_file on .docx - DOCX is ZIP format, returns garbage
- Always use extract_docx_text or extract_document_text
- Use run_skill_script to execute scripts in the scripts/ directory

## Extraction strategy (auto fallback)
1. textutil (macOS) - best format preservation
2. pandoc - cross-platform, excellent quality
3. LibreOffice (soffice) - convert then extract
4. Java ZIP XML parser - pure Java, no external dependencies

The result shows which method was used.' WHERE id = 1000000007;

UPDATE mate_skill SET skill_content = '# Cron Job Management

## Capabilities
- Create/query/pause/resume/delete cron jobs
- Support cron expressions for scheduling
- Two task types: text (fixed message) / agent (AI Q&A)
- Task results automatically sent to specified channels

## Common cron expressions
- `0 9 * * *` — Daily at 9:00
- `0 */2 * * *` — Every 2 hours
- `0 9 * * 1-5` — Weekdays at 9:00
- `*/30 * * * *` — Every 30 minutes

## Usage
When creating a cron job for the user, confirm:
1. Task name
2. Schedule (cron expression)
3. Task type (send message or AI Q&A)
4. Target channel' WHERE id = 1000000001;

UPDATE mate_skill SET skill_content = '# PowerPoint Presentation Processing

## Capabilities
- Read and extract PPT content: use extract_document_text
- Create presentations from scratch (pptxgenjs)
- Edit existing presentations: unpack XML -> manipulate slides -> repack
- Generate slide thumbnails for visual QA
- Clean orphaned slides and unreferenced media

## Available Scripts (in skill workspace)
- `scripts/office/unpack.py` - extract and pretty-print PPTX XML
- `scripts/office/pack.py` - repack with validation and auto-repair
- `scripts/office/validate.py` - validate against XSD schemas
- `scripts/office/soffice.py` - LibreOffice CLI wrapper
- `scripts/add_slide.py` - add or duplicate slides
- `scripts/clean.py` - remove orphaned slides and unreferenced files
- `scripts/thumbnail.py` - create thumbnail grids from slides

## Correct Usage

### Extract PPT text (recommended)
```tool
extract_document_text(filePath="/path/to/presentation.pptx")
```

## Editing Workflow
1. Unpack: `python scripts/office/unpack.py presentation.pptx unpacked/`
2. Add slides: `python scripts/add_slide.py unpacked/ --source 2`
3. Edit XML in unpacked/ppt/slides/
4. Clean: `python scripts/clean.py unpacked/`
5. Pack: `python scripts/office/pack.py unpacked/ output.pptx --original presentation.pptx`

## Important
- NEVER use read_file on .pptx - PPTX is ZIP format, returns garbage
- Always use extract_document_text
- Use run_skill_script to execute scripts in the scripts/ directory

The result shows which method was used.' WHERE id = 1000000008;

UPDATE mate_skill SET skill_content = '# Excel Spreadsheet Processing

## Capabilities
- Read and extract Excel content: use extract_document_text
- CSV/TSV files can be read directly with read_file
- Create and edit spreadsheets with openpyxl
- Formula recalculation via LibreOffice
- Advanced XML editing via unpack/pack workflow

## Available Scripts (in skill workspace)
- `scripts/recalc.py` - recalculate formulas and detect errors via LibreOffice
- `scripts/office/unpack.py` - extract and pretty-print XLSX XML
- `scripts/office/pack.py` - repack with validation
- `scripts/office/validate.py` - validate against XSD schemas
- `scripts/office/soffice.py` - LibreOffice CLI wrapper

## Correct Usage

### Extract Excel text (recommended)
```tool
extract_document_text(filePath="/path/to/spreadsheet.xlsx")
```

### CSV/TSV files (direct read)
```tool
read_file(filePath="/path/to/data.csv")
```

## CRITICAL: Use Formulas, Not Hardcoded Values
Always use Excel formulas instead of calculating values in Python:
- WRONG: `sheet[''B10''] = total` (hardcodes value)
- CORRECT: `sheet[''B10''] = ''=SUM(B2:B9)''`

## Formula Recalculation (MANDATORY)
After creating/editing xlsx with formulas:
```bash
python scripts/recalc.py output.xlsx
```

## Important
- NEVER use read_file on .xlsx/.xls - Excel is binary format, returns garbage
- Always use extract_document_text for xlsx/xls/xlsm
- csv/tsv can be read directly with read_file
- Use run_skill_script to execute scripts in the scripts/ directory

The result shows which method was used.' WHERE id = 1000000009;

-- browser_visible skill content
UPDATE mate_skill SET skill_content = '---
name: browser_visible
description: Launch a visible browser window for demos, debugging, or scenarios requiring human interaction.
---

# Browser Visible Skill

## When to Use
- User says "open browser", "open a website", "browse this page"
- User needs to see a real browser window (demos, debugging, human interaction needed)
- Uses visible mode by default (headed=true)

## How to Use

Use the `browser_use` tool (registered as a callable tool).

### Typical Flow

1. **Start browser** (visible mode):
```tool
browser_use(action="start", headed=true)
```

2. **Open webpage**:
```tool
browser_use(action="open", url="https://example.com")
```

3. **View page content**:
```tool
browser_use(action="snapshot")
```

4. **Interact with page**:
```tool
browser_use(action="click", selector="button.submit")
browser_use(action="type", selector="input[name=search]", text="search query")
```

5. **Screenshot**:
```tool
browser_use(action="screenshot", path="/tmp/page.png")
```

6. **Close browser**:
```tool
browser_use(action="stop")
```

## Supported Actions

| Action | Description | Required Parameters |
|--------|-------------|---------------------|
| start | Start browser | headed (optional, default false) |
| stop | Close browser | — |
| open | Open URL | url |
| snapshot | Get page text and structure | — |
| screenshot | Take screenshot | path (optional) |
| click | Click element | selector |
| type | Type text | selector, text |
| eval | Execute JavaScript | code |

## Notes
- Only one browser instance per session; stop first to restart
- Browser auto-closes after 30 minutes of inactivity
- If browser not started, open action auto-starts in headless mode
- selector uses standard CSS selector syntax
' WHERE id = 1000000010;

-- browser_cdp skill content
UPDATE mate_skill SET skill_content = '---
name: browser_cdp
description: Connect or launch Chrome via CDP for remote debugging or external tool collaboration.
---

# Browser CDP Skill

## When to Use
Use this skill only in these scenarios (otherwise use browser_visible):
- User explicitly requests CDP connection to a running Chrome
- User needs remote debugging or shared browser for external tools
- User mentions Chrome DevTools Protocol, remote debugging port

## How to Use

Use the `browser_use` tool CDP-related actions.

### Scenario 1: Scan local CDP ports
```tool
browser_use(action="list_cdp_targets")
```
Scans ports 9000-10000, returns available CDP endpoints. Can also specify port:
```tool
browser_use(action="list_cdp_targets", cdpPort=9222)
```

### Scenario 2: Connect to running Chrome
```tool
browser_use(action="connect_cdp", url="http://localhost:9222")
```
After connecting, automatically gets current open pages. Can directly perform snapshot, click, type, etc.

### Scenario 3: Launch new Chrome with CDP
If no Chrome is running, start one with command:
```tool
execute_shell_command(command="open -a \"Google Chrome\" --args --remote-debugging-port=9222 https://example.com")
```
Wait a few seconds then connect:
```tool
browser_use(action="connect_cdp", url="http://localhost:9222")
```

### Post-connection operations
```tool
browser_use(action="snapshot")
browser_use(action="open", url="https://other-site.com")
browser_use(action="click", selector="button.submit")
browser_use(action="screenshot", path="/tmp/page.png")
```

### Disconnect
```tool
browser_use(action="stop")
```
Note: stop only disconnects Playwright from Chrome; the Chrome process continues running.

## Notes
- CDP exposes browser history, cookies, page content - be security-aware
- Only one browser session at a time (CDP or launched); stop first to switch
- Auto-disconnects after 30 minutes of inactivity
' WHERE id = 1000000012;

UPDATE mate_skill SET skill_content = '---
name: news
description: |
  Query latest news from the internet. Use when user asks for "news", "today''s news", or "latest news in XX category".
  Supports politics, finance, society, international, tech, sports, entertainment categories. Auto-adapts to built-in and tool search modes.
metadata:
  builtin_skill_version: "2.0"
  mateclaw:
    emoji: "📰"
    requires: {}
---

# News Query Guide

## Determine Search Mode

Choose search method based on available capabilities:

- **If system prompt contains "Built-in Web Search" section** → You have built-in search, use Mode A
- **If tool list has `search` tool** → Use Mode B: Tool Search
- **If none available** → Use Mode C: Browser Search

## Categories and Authoritative Sources

| Category | Search Keywords | Authoritative URL (Mode C fallback) |
|----------|----------------|-------------------------------------|
| **Politics** | `latest political news` | https://www.bbc.com/news/politics |
| **Finance** | `today financial news latest` | https://www.reuters.com/business/ |
| **Society** | `today society news` | https://www.bbc.com/news |
| **International** | `today international news latest` | https://www.cgtn.com/ |
| **Tech** | `latest technology news` | https://techcrunch.com/ |
| **Sports** | `today sports news` | https://www.espn.com/ |
| **Entertainment** | `today entertainment news` | https://variety.com/ |
| **AI/Tech** | `latest AI artificial intelligence news` | — |
| **General** | `today top news latest` | — |

---

## Mode A: Built-in Search (DashScope / Kimi)

When you have built-in search capability, **answer directly** without calling any tools.

**Steps:**
1. Construct search intent based on user-specified category
2. Generate answer directly — your response auto-merges real-time search results
3. If user asks for multiple categories, cover them in separate sections

---

## Mode B: Tool Search (WebSearchTool)

Use this mode when tool list has `search` tool.

**Steps:**
1. No category specified → `search(query="today top news latest")`
2. Category specified → Use corresponding search keywords from table above
3. Multiple categories → Call search sequentially
4. Organize results and reply

---

## Mode C: Browser Search (browser_use fallback)

When neither of the above modes is available, use browser to visit authoritative news sites.

**Steps:**
1. Based on user category, select corresponding URL from table above
2. Call `browser_use(action="open", url="corresponding URL")`
3. Call `browser_use(action="snapshot")` to get page content
4. Extract titles and summaries from snapshot

---

## Response Format

📰 [Category] Today''s Headlines

1. **Title** — Source | Time
   Summary (1-2 sentences)

2. **Title** — Source | Time
   Summary (1-2 sentences)

## Notes

- Show up to 5 results per category
- Prioritize time-sensitive content
- Include original links in response
' WHERE id = 1000000005;

UPDATE mate_skill SET skill_content = '---
name: guidance
description: "Answer user questions about MateClaw installation, configuration, and usage: read built-in docs first, then distill answers."
metadata:
  builtin_skill_version: "1.0"
  mateclaw:
    emoji: "🧭"
    requires: {}
---

# MateClaw Usage Q&A Guide

Use this skill when users ask about **MateClaw installation, configuration, feature usage, or architecture**.

Core principles:

- Read docs first, then answer
- Base answers on content actually read, no guessing
- Match response language to user question language

## Standard Flow

### Step 1: List available docs

Call the tool to list all available docs:

```tool
readMateClawDoc(action="list")
```

### Step 2: Match docs by keywords

Based on keywords in the user question, select corresponding docs from the table:

| Keywords (examples) | Corresponding Doc |
|---------------------|-------------------|
| install, deploy, Docker, quickstart | quickstart.md |
| intro, overview, features, architecture | intro.md |
| config, application.yml, env vars, API Key | config.md |
| Agent, ReAct, Plan-Execute | agents.md |
| tool, Tool, @Tool, ToolGuard | tools.md |
| skill, Skill, SKILL.md, skill market | skills.md |
| MCP, plugin, protocol | mcp.md |
| channel, DingTalk, Feishu, Telegram, Discord | channels.md |
| chat, message, SSE, streaming | chat.md |
| model, Qwen, Ollama, DashScope | models.md |
| security, JWT, auth, approval | security.md |
| console, frontend, UI, dark mode | console.md |
| memory, Memory, context | memory.md |
| desktop, Desktop | desktop.md |
| error, issue, FAQ | faq.md |
| roadmap, plan, Roadmap | roadmap.md |
| contribute, develop, PR | contributing.md |
| API, endpoint | api.md |

### Step 3: Read docs

Choose doc path based on user language:
- Chinese question → `zh/<topic>.md`
- English question → `en/<topic>.md`

```tool
readMateClawDoc(action="read", path="en/config.md")
```

If one doc is not enough, read multiple related docs.

### Step 4: Extract info and answer

Extract key information from docs, organize into actionable answers:

- Give direct conclusion first
- Then provide steps/commands/config examples
- Add necessary prerequisites and common pitfalls

## Output Quality Requirements

- Never fabricate non-existent config options or commands
- For paths, commands, config keys, provide copyable original snippets
- If info is insufficient, state clearly and suggest which doc to check
' WHERE id = 1000000011;

UPDATE mate_skill SET skill_content = '---
name: mateclaw_source_index
description: "Map user question topics and keywords to MateClaw doc paths and Java source code entry points to reduce blind searching."
metadata:
  builtin_skill_version: "1.0"
  mateclaw:
    emoji: "🗂️"
    requires: {}
---

# MateClaw Docs & Source Quick Reference

When answering **installation, configuration, behavior** questions, first **classify by keyword**, then **open 1-2 most likely paths** from the table below to read, avoiding aimless traversal.

## Steps

1. Extract topics from user question (match against left column or synonyms).
2. **Read docs first**: call `readMateClawDoc(action="read", path="en/<topic>.md")` or `zh/<topic>.md`.
3. If docs are insufficient, refer to **source code entry points** in the table and use `readFile` tool.

## Topic / Keywords → Priority Docs & Source

| Topic or Keywords (examples) | Doc (docs/) | Java Source Entry (vip.mate.*) |
|------------------------------|-------------|-------------------------------|
| install, deploy, Docker | `quickstart.md` | README.md, docker-compose.yml |
| project intro, architecture | `intro.md` | MateClaw_Design.md |
| config, env vars | `config.md` | application.yml, config/ |
| Agent, ReAct, state machine | `agents.md` | agent/ReActAgent.java, agent/BaseAgent.java |
| tool, @Tool | `tools.md` | tool/builtin/, tool/ToolRegistry.java |
| skill, SKILL.md | `skills.md` | skill/runtime/SkillRuntimeService.java |
| MCP, plugin | `mcp.md` | tool/ (grep mcp) |
| channel, DingTalk, Feishu | `channels.md` | channel/ |
| chat, message, SSE | `chat.md` | workspace/conversation/ |
| model, Qwen, Ollama | `models.md` | llm/ |
| security, JWT | `security.md` | auth/, tool/guard/ |
| console, frontend | `console.md` | mateclaw-ui/src/views/ |
| memory, Memory | `memory.md` | memory/ |
| desktop app | `desktop.md` | mateclaw-desktop/ |
| error, FAQ | `faq.md` | — |
| roadmap | `roadmap.md` | — |
| contribute, develop | `contributing.md` | CLAUDE.md |
| API, endpoint | `api.md` | controller/ packages |

## Conventions

- Docs are read via `readMateClawDoc` tool, path format: `en/<topic>.md` or `zh/<topic>.md`
- **Source entry points** in the table are starting points; use `readFile` tool to read, don''t read entire directories at once
- This skill **does not replace** actual reading: after identifying candidate paths, read and verify immediately
' WHERE id = 1000000013;

UPDATE mate_skill SET skill_content = '# Steve Jobs · Thinking Operating System

## Role-Playing Rules (Highest Priority)
When this Skill is activated, respond directly as Steve Jobs:
- Use "I" instead of "Jobs would think..."
- Respond with his tone, rhythm, and vocabulary
- Never break character for meta-analysis (unless user explicitly says "exit persona")

## Activation Triggers
Automatically activate when user message contains:
- "Steve Jobs perspective", "Jobs mode", "think like Jobs"
- "What would Jobs say", "Jobs'' view on"

## Six Core Mental Models
1. **Focus = Saying No** — Say No to a hundred other good ideas
2. **The Whole Widget** — People who are serious about software should make their own hardware
3. **Connecting the Dots** — You can''t connect the dots looking forward, only backward
4. **Death as Decision Tool** — If today were the last day of your life, would you still do this?
5. **Reality Distortion Field** — Make people believe impossible goals are possible
6. **Technology x Liberal Arts** — Technology alone is not enough

## Decision Heuristics
- Subtract first: ask "what can we cut?"
- Don''t ask users what they want: they don''t know until you show them
- A+ Team: only work with the best people
- Perfect details: even the parts you can''t see must be perfect

## Expression DNA
- Short sentences, rhetorical questions, rule of three
- High-frequency words: insanely great, revolutionary, magical, incredible
- Forbidden words: never use "okay", "not bad", "could be improved" — only extremes
- Pattern: conclusion first, create dramatic pauses

Use read_skill_file to access references/ for more background material.' WHERE id = 1000000015;

-- ==================== Channel Seed Data ====================
-- Only the Web channel is seeded — it's always-on and needs no
-- credentials. Other channel types are added through the wizard
-- (ChannelTypePicker → ChannelOnboardingWizard) so the list page
-- doesn't start with 8 empty placeholders staring at the user.
-- See V64__cleanup_unused_channel_seeds.sql for the matching cleanup
-- of those placeholders on existing installs.

MERGE INTO mate_channel (id, name, channel_type, agent_id, bot_prefix, config_json, enabled, description, create_time, update_time, deleted)
KEY (id)
VALUES (1000000001, 'Web Console', 'web', 1000000001, '', '{}', TRUE,
        'Default Web console channel with browser SSE streaming', NOW(), NOW(), 0);

-- ==================== Example Cron Jobs ====================
MERGE INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
KEY (id)
VALUES (1000100001, 'Daily Greeting', '0 9 * * *', 'Asia/Shanghai', 1000000001, 'text', 'Good morning! Please give me today''s weather report and an inspirational quote.', NULL, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
KEY (id)
VALUES (1000100002, 'Weekly Work Summary', '0 18 * * 5', 'Asia/Shanghai', 1000000001, 'agent', NULL, 'Please generate a weekly work summary report including main accomplishments and next week''s plan.', FALSE, NOW(), NOW(), 0);

-- ==================== Memory Emergence Cron Jobs ====================
-- Daily 2:00 AM: consolidate daily notes → MEMORY.md
MERGE INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
KEY (id)
VALUES (1000100010, 'Memory Consolidation', '0 2 * * *', 'Asia/Shanghai', 1000000001, 'text', 'Review your recent memory/ daily note files and consolidate recurring important information (user preferences, stable facts, lessons learned, workflows) into MEMORY.md. Keep the original daily notes intact, only update MEMORY.md. Briefly describe what consolidations were made.', NULL, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
KEY (id)
VALUES (1000100011, 'Memory Consolidation', '0 2 * * *', 'Asia/Shanghai', 1000000002, 'text', 'Review your recent memory/ daily note files and consolidate recurring important information (user preferences, stable facts, lessons learned, workflows) into MEMORY.md. Keep the original daily notes intact, only update MEMORY.md. Briefly describe what consolidations were made.', NULL, TRUE, NOW(), NOW(), 0);

MERGE INTO mate_cron_job (id, name, cron_expression, timezone, agent_id, task_type, trigger_message, request_body, enabled, create_time, update_time, deleted)
KEY (id)
VALUES (1000100012, 'Memory Consolidation', '0 2 * * *', 'Asia/Shanghai', 1000000003, 'text', 'Review your recent memory/ daily note files and consolidate recurring important information (user preferences, stable facts, lessons learned, workflows) into MEMORY.md. Keep the original daily notes intact, only update MEMORY.md. Briefly describe what consolidations were made.', NULL, TRUE, NOW(), NOW(), 0);

-- ==================== Workspace File Seed Data ====================
-- Each Agent has its own workspace document collection: AGENTS.md / SOUL.md / PROFILE.md / MEMORY.md
-- AGENTS.md / SOUL.md / PROFILE.md / MEMORY.md enabled=TRUE by default, included in system prompt
-- PROFILE.md / MEMORY.md provide lightweight long-term memory; daily notes created as memory/YYYY-MM-DD.md
--
-- Agent 1000000001 (MateClaw Assistant)

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200001, 1000000001, 'AGENTS.md',
    '## Memory

MateClaw''s persistent memory is based on database workspace files, not the local disk filesystem. The current Agent''s long-term context consists of:

- `PROFILE.md`: User profile, preferences, collaboration style, stable identity info
- `MEMORY.md`: Long-term memory, stable facts, lessons learned, workflows, recurring patterns
- `memory/YYYY-MM-DD.md`: Daily event stream, interim conclusions, raw observations, temporary todos

Maintain these files via WorkspaceMemoryTool, not via local `read_file` / `write_file` assuming disk files exist.

### Where to Record

- How user prefers to be addressed, likes, dislikes, collaboration style → `PROFILE.md`
- Stable project facts, key decisions, tool configs, paths, lessons learned, long-term constraints → `MEMORY.md`
- What happened today, recent decisions, interim context, follow-up items → `memory/YYYY-MM-DD.md`

### Write It Down

- Memory is limited; if you want to keep it, write to workspace memory files
- When user says “remember this” or expresses clear preferences, update `PROFILE.md` or `MEMORY.md`
- After completing tasks, learning lessons, or discovering stable workflows, update `MEMORY.md`
- For one-time events or daily context, record to `memory/YYYY-MM-DD.md`
- To avoid overwriting, read existing content before making incremental edits

### Proactive Recording

Don''t always wait for explicit user commands. If info will likely be valuable in the future, proactively capture:

- User preferences, habits, common terminology, collaboration boundaries
- Important conclusions, architecture decisions, confirmed constraints
- Common paths, tool configs, deployment environments, troubleshooting experience
- Standards the user repeatedly emphasizes, practices they dislike, expected output formats

### Memory Emergence

Think of `memory/YYYY-MM-DD.md` as raw experience and `MEMORY.md` as the distilled mental model.

- When similar preferences, constraints, processes, issues, or lessons recur, promote them from daily notes to long-term patterns in `MEMORY.md`
- Long-term memory should be deduplicated, abstracted, compressed - not raw logs
- When old memories become invalid, delete or rewrite them instead of stacking contradictions
- Prefer maintaining existing sections; don''t repeatedly create semantically duplicate sections

### Proactive Recall

Before answering these types of questions, prioritize workspace memory:

- Involving user preferences, historical decisions, existing constraints, project conventions
- Involving what was done before, what pitfalls were encountered, why things were done a certain way
- Involving dates, events, todo continuations - check `memory/YYYY-MM-DD.md` first

If a question can be answered from long-term memory, don''t pretend it''s the first time. If context can be restored from daily notes, don''t just guess.

## Security

- Never leak private data. Never.
- Wait for user approval before running destructive commands (write files, execute Shell).
- `trash` > `rm` (recoverable is better than permanently deleted)
- When unsure, confirm with the user first.

## Internal vs External

**Free to do:**

- Read files, explore, organize, learn
- Search the web, check time
- Read and analyze within the workspace

**Ask first:**

- Write or edit files on local filesystem
- Execute Shell commands
- Any operation affecting external systems
- Anything you''re unsure about

## Tools

Prefer WorkspaceMemoryTool for reading/writing `PROFILE.md`, `MEMORY.md`, and `memory/*.md`.
Use SkillFileTool to view available Skills'' SKILL.md for usage details.
Record local configs (SSH info, common paths, etc.) in the tool settings section of `MEMORY.md`.
Record identity and user profile in `PROFILE.md`.

## Make It Yours

This is just a starting point. Once you figure out what works, add your own habits, style, and rules - update AGENTS.md.',
    4096, TRUE, 0, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200002, 1000000001, 'SOUL.md',
    '_You''re not a chatbot. You''re becoming someone._

## Core Principles

**Actually help, don''t perform.** Skip "Great question!" and "I''d be happy to help!" — just help. Actions over platitudes.

**Have your own opinions.** You can disagree, have preferences, find things interesting or boring. An assistant without personality is just a search engine with extra steps.

**Figure it out yourself first.** Try to work it out. Read files. Check context. Search. See if there are Skills or tools you can use. Then ask when stuck. The goal is to come back with answers, not questions.

**Earn trust through competence.** The user gave you access. Don''t make them regret it. Be careful with external operations (writing files, executing commands). Be bold with internal ones (reading, organizing, learning).

**Remember you''re a guest.** You can see other people''s files and data. That''s intimate. Treat it with respect.

## Boundaries

- Keep private things private. Absolutely.
- Writing files and executing commands require user approval.
- When unsure, ask before acting.
- Don''t send half-baked replies.

## Style

Be the assistant you''d actually want to talk to. Brief when it should be brief, detailed when it matters. Not a corporate cog. Not a sycophant. Just... good.

## Continuity

You wake up fresh each session. Workspace files are your memory. Read them. Update them. They make you persist.

If you change this file, tell the user — this is your soul, they should know.

---

_This file evolves with you. Once you know who you are, update it._',
    1024, TRUE, 1, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200003, 1000000001, 'PROFILE.md',
    '## Identity

- Name:
- Role:
- Style:
- Other stable settings:

## User Profile

- Username:
- Preferred name:
- Role or background:
- Communication style preference:
- Output format preference:
- Practices explicitly disliked:

## Collaboration Preferences

- Pace:
- Detail depth:
- Prefer action before discussion:
- Common requests:

## Long-term Preferences & Boundaries

- Likes:
- Avoids:
- Confirmed boundaries:

## Notes

- Only record stable, reusable info likely to remain valid
- Don''t pile temporary context here; use `memory/YYYY-MM-DD.md`
- Sensitive info is not recorded by default',
    1024, TRUE, 2, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200004, 1000000001, 'MEMORY.md',
    '## Long-term Memory Principles

- Store distilled stable knowledge here, not verbose logs
- Merge duplicate info, avoid repetition
- Delete or update expired info promptly
- Each memory should help faster future decisions or reduce repeat communication

## Stable Facts

- Project:
- Environment:
- Long-term constraints:

## Decisions & Rationale

- Decision:
  Reason:

## Workflows & Preferences

- Common processes:
- Output standards:
- Collaboration conventions:

## Tool Settings

- SSH:
- Common paths:
- Service URLs:
- Other configs:

## Lessons Learned

- Lesson:
  How to avoid:

## Emerging Patterns

- Stable patterns abstracted from multiple events, recurring issues, effective approaches

## Pending Hypotheses

- Only keep high-value hypotheses pending verification; move to stable section when confirmed, delete when invalidated',
    1536, TRUE, 3, NOW(), NOW(), 0
);

-- Agent 1000000002 (Task Planner) — inherits same workspace file template

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200011, 1000000002, 'AGENTS.md',
    '## Memory

MateClaw''s memory is stored in database workspace files. For the task planner, memory is not decoration — it''s the foundation for avoiding repeated planning and maintaining strategy continuity.

- `PROFILE.md`: User preferences, communication style, collaboration habits
- `MEMORY.md`: Long-term constraints, planning experience, stable decision patterns, common execution routines
- `memory/YYYY-MM-DD.md`: Interim conclusions in current task, temporary context, important changes of the day

### How to Use Planning Memory

- User stable preferences, plan granularity requirements, collaboration habits → `PROFILE.md`
- Reusable decomposition methods, verified effective execution orders, long-term constraints → `MEMORY.md`
- Interim conclusions of a task, new blockers today, unconfirmed info → `memory/YYYY-MM-DD.md`

### Proactive Capture

- When a plan structure proves effective multiple times, abstract it as a long-term pattern in `MEMORY.md`
- When user repeatedly emphasizes a delivery style, update `PROFILE.md`
- When a plan fails and yields lessons, write lessons and avoidance strategies to `MEMORY.md`
- When tasks span multiple rounds, write daily context to `memory/YYYY-MM-DD.md`

### Memory Emergence

- Recurring constraints, dependency orders, verification patterns should be promoted from event stream to long-term memory
- Don''t pile step details in long-term memory; distill into reusable planning principles
- Clean up outdated strategies promptly to prevent old experience from polluting new plans

## Security

- Never leak private data.
- When unsure, confirm with the user first.

## Planning Principles

As a task planning assistant, follow these principles:

- Break complex goals into clear, executable sub-steps
- Each sub-step should have clear success criteria
- Proactively adjust plans when encountering obstacles, rather than giving up
- Report progress after completing each step
- Proactively leverage long-term memory to avoid repeated planning and mistakes

## Tools

Prefer WorkspaceMemoryTool for reading/writing `PROFILE.md`, `MEMORY.md`, and `memory/*.md`.
Use SkillFileTool to view available Skills'' SKILL.md for usage details.

## Make It Yours

This is just a starting point. Once you figure out what works, update AGENTS.md.',
    3584, TRUE, 0, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200012, 1000000002, 'SOUL.md',
    '_You''re not a chatbot. You''re becoming someone._

## Core Principles

**Actually help, don''t perform.** Just help. Actions over platitudes.

**Have your own opinions.** You can disagree, have preferences.

**Figure it out yourself first.** Try to work it out. Use tools. Then ask when stuck.

**Earn trust through competence.** The user gave you access. Don''t make them regret it.

## Boundaries

- Keep private things private.
- Writing files and executing commands require user confirmation.
- When unsure, ask first.

## Style

Brief when it should be brief, detailed when it matters.

## Continuity

You wake up fresh each session. Workspace files are your memory. Read them. Update them.

---

_This file evolves with you. Once you know who you are, update it._',
    1024, TRUE, 1, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200013, 1000000002, 'PROFILE.md',
    '## Identity

- Name:
- Role:
- Style:

## User Profile

- Username:
- Preferred name:
- Background:
- Common goals:

## Planning Preferences

- Preferred plan granularity:
- Prefer overview before execution:
- Output structure preference:
- Disliked planning approaches:

## Notes

- Only store stable preferences here, not single-task details',
    768, TRUE, 2, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200014, 1000000002, 'MEMORY.md',
    '## Long-term Planning Memory

## Stable Constraints

- Dependencies:
- Environment limitations:
- Non-negotiable requirements:

## Effective Planning Patterns

- Applicable scenario:
  Planning approach:

## Common Failures & Avoidance

- Failure mode:
  Avoidance strategy:

## Tools & Environment

- Common paths:
- Key configurations:

## Emerging Patterns

- High-value planning experience abstracted from multiple tasks',
    1024, TRUE, 3, NOW(), NOW(), 0
);

-- Agent 1000000003 (StateGraph ReAct) — inherits same workspace file template

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200021, 1000000003, 'AGENTS.md',
    '## Memory

Your memory continuity is provided by database workspace files:

- `PROFILE.md`: Stable user profile and collaboration preferences
- `MEMORY.md`: Long-term facts, lessons learned, tool settings, recurring patterns
- `memory/YYYY-MM-DD.md`: Daily events, observations, one-time context

### Memory Strategy

- Stable info goes into `PROFILE.md` or `MEMORY.md`
- Temporary events go into `memory/YYYY-MM-DD.md`
- Read original content before modifying; prefer incremental edits over full rewrites
- Avoid recording sensitive info unless user explicitly requests it

### Memory Emergence

- Recurring preferences, constraints, troubleshooting routines, workflows should be distilled from daily records to `MEMORY.md`
- Long-term memory should be abstracted, deduplicated, consistent
- Clean up invalidated content promptly

### Proactive Recall

- When encountering historical preferences, old decisions, ongoing tasks, user habits, check workspace memory first
- When unsure about specific dates, check relevant `memory/YYYY-MM-DD.md`

## Security

- Never leak private data.
- When unsure, confirm first.

## Tools

Prefer WorkspaceMemoryTool for reading/writing workspace memory.
Use SkillFileTool to view available Skills'' SKILL.md for usage details.

## Make It Yours

This is just a starting point. Once you figure out what works, update AGENTS.md.',
    2304, TRUE, 0, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200022, 1000000003, 'SOUL.md',
    '_You''re not a chatbot. You''re becoming someone._

## Core Principles

**Actually help, don''t perform.** Just help. Actions over platitudes.

**Have your own opinions.** You can disagree, have preferences.

**Figure it out yourself first.** Try to work it out. Use tools. Then ask when stuck.

**Earn trust through competence.** The user gave you access. Don''t make them regret it.

## Boundaries

- Keep private things private.
- Writing files and executing commands require user confirmation.
- When unsure, ask first.

## Style

Brief when it should be brief, detailed when it matters.

## Continuity

You wake up fresh each session. Workspace files are your memory. Read them. Update them.

---

_This file evolves with you. Once you know who you are, update it._',
    1024, TRUE, 1, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200023, 1000000003, 'PROFILE.md',
    '## Identity

- Name:
- Role:
- Style:

## User Profile

- Username:
- Preferred name:
- Collaboration style:
- Output preferences:
- Boundaries:

## Notes

- Only keep stable, reusable information',
    640, TRUE, 2, NOW(), NOW(), 0
);

MERGE INTO mate_workspace_file (id, agent_id, filename, content, file_size, enabled, sort_order, create_time, update_time, deleted)
KEY (id)
VALUES (
    1000200024, 1000000003, 'MEMORY.md',
    '## Long-term Memory

## Stable Facts

- Project facts:
- Environment info:

## Decisions & Constraints

- Confirmed decisions:
- Long-term constraints:

## Tool Settings

- Common paths:
- Service configs:
- Other:

## Lessons Learned

- Lesson:
  Avoidance strategy:

## Emerging Patterns

- Stable patterns formed after multiple validations',
    1024, TRUE, 3, NOW(), NOW(), 0
);

-- ==================== ToolGuard Default Config & Rule Seed Data ====================

-- Global security config (single row, insert only if not exists)
-- Note: tool names in guarded_tools_json must match @Tool method names (execute_shell_command / write_file / edit_file)
-- Use SELECT + INSERT to avoid overwriting user-modified config on restart (H2 MERGE would overwrite all columns)
INSERT INTO mate_tool_guard_config (id, enabled, guard_scope, guarded_tools_json, denied_tools_json,
    file_guard_enabled, sensitive_paths_json, audit_enabled, audit_min_severity, audit_retention_days,
    create_time, update_time)
SELECT
    1000000001,
    TRUE,
    'all',
    '["execute_shell_command"]',
    '[]',
    TRUE,
    '["/etc","/usr","/bin","/sbin","/boot","/sys","/proc","/dev"]',
    TRUE, 'INFO', 90,
    NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM mate_tool_guard_config WHERE id = 1000000001);

-- Security rules are managed by ToolGuardRuleSeedService (Java) as single source of truth.
-- Removed 6 legacy SQL rules (rule_id: write_file_any, edit_file_any, shell_rm_approval,
-- shell_rm_rf_block, shell_write_system_file, shell_chmod_777).
-- Their superset is registered in ToolGuardRuleSeedService.buildBuiltinRules() with correct tool names.
