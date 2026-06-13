-- Refresh the Bailian (Aliyun Model Studio) Qwen catalog to match the 2026 Q2
-- model lineup, and remove a non-existent model id that triggered 400
-- InvalidParameter on the native text-generation endpoint.
--
-- Why now:
--   * Reported user error: "Bad request, please check input (type=MODEL_NOT_FOUND)"
--     when chatting with the seeded "Qwen3 Plus" entry.
--   * Root cause: model id qwen3-plus does not exist on Bailian. The real
--     balanced Qwen3 series uses dotted minor versions (qwen3.5-plus,
--     qwen3.6-plus); plain qwen3-plus was never published. DashScope's
--     native endpoint rejects it with [InvalidParameter], which our
--     failover classifier maps to MODEL_NOT_FOUND and evicts the entire
--     dashscope provider from the pool.
--
-- Two-part fix:
--   1. Soft-delete the bogus qwen3-plus row (id 1000000172).
--   2. Seed three latest-snapshot trackers on the dashscope native provider
--      (qwen-plus-latest / qwen-max-latest / qwen-turbo-latest) and six
--      newer Qwen3 series models on the bailian-team OpenAI-compat provider
--      where they are documented to work (vision + flash + coder + 3.6 snapshot).

-- 1. Soft-delete the bogus model id (idempotent).
UPDATE mate_model_config
   SET deleted = 1, enabled = 0, update_time = NOW()
 WHERE id = 1000000172
   AND model_name = 'qwen3-plus';

-- 2a. Latest-snapshot trackers on dashscope native (text-generation endpoint).
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000174, 'Qwen Plus (latest)',  'dashscope', 'qwen-plus-latest',  'Latest stable snapshot of Qwen Plus — auto-updates as Bailian rolls new releases.', 0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000175, 'Qwen Max (latest)',   'dashscope', 'qwen-max-latest',   'Latest stable snapshot of Qwen Max — strongest reasoning capability.',             0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000176, 'Qwen Turbo (latest)', 'dashscope', 'qwen-turbo-latest', 'Latest stable snapshot of Qwen Turbo — low latency, high frequency.',              0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, model_type=EXCLUDED.model_type, update_time=EXCLUDED.update_time;

-- 2b. Newer Qwen3 series on the bailian-team OpenAI-compat token plan endpoint.
INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, model_type, create_time, update_time, deleted)
VALUES
(1000000407, 'Qwen 3.5 Plus',            'bailian-team', 'qwen3.5-plus',           'Bailian Token Plan — Qwen3.5 balanced flagship, hybrid thinking, 128K context.',  0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000408, 'Qwen 3.5 Flash',           'bailian-team', 'qwen3.5-flash',          'Bailian Token Plan — Qwen3.5 fast variant, lower latency for high-frequency calls.', 0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000409, 'Qwen3 VL Plus',            'bailian-team', 'qwen3-vl-plus',          'Bailian Token Plan — Qwen3 vision-language flagship, image + video understanding.',   0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000410, 'Qwen3 VL Flash',           'bailian-team', 'qwen3-vl-flash',         'Bailian Token Plan — Qwen3 vision-language fast variant for high-throughput vision.', 0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000411, 'Qwen3 Coder Plus',         'bailian-team', 'qwen3-coder-plus',       'Bailian Token Plan — Qwen3 coding flagship, agentic code editing and tool use.',     0.2, 8192, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000412, 'Qwen 3.6 Plus 2026-04-02', 'bailian-team', 'qwen3.6-plus-2026-04-02','Bailian Token Plan — pinned snapshot of Qwen 3.6 Plus released 2026-04-02.',         0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000413, 'Qwen 3.6 Max (preview)',  'bailian-team', 'qwen3.6-max-preview',    'Bailian Token Plan — Qwen3.6 Max preview, strongest reasoning in the 3.6 lineup.',   0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000414, 'Qwen 3.6 Flash',           'bailian-team', 'qwen3.6-flash',          'Bailian Token Plan — Qwen3.6 fast variant, hybrid thinking mode default-on.',         0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000415, 'Qwen 3.6 Flash 2026-04-16','bailian-team', 'qwen3.6-flash-2026-04-16','Bailian Token Plan — pinned snapshot of Qwen 3.6 Flash released 2026-04-16.',        0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0),
(1000000416, 'Qwen 3.5 Omni Plus',       'bailian-team', 'qwen3.5-omni-plus',      'Bailian Token Plan — Qwen3.5 omni-modal plus, text + vision + audio in/out.',         0.7, 4096, 0.8, 1, 1, 0, 'chat', NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, description=EXCLUDED.description, model_type=EXCLUDED.model_type, update_time=EXCLUDED.update_time;
