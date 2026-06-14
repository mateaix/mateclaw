-- V15: Purge DashScope model seed rows that are unavailable on the native protocol
DELETE FROM mate_model_config
WHERE id IN (1000000170, 1000000171)
  AND provider = 'dashscope'
  AND builtin = TRUE;
-- 1000000170 = qwen3.5-plus
-- 1000000171 = qwen3.5-max
