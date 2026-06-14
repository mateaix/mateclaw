-- V16: Broaden the DashScope native-protocol purge (see V15).
DELETE FROM mate_model_config
WHERE provider = 'dashscope'
  AND (model_name LIKE 'qwen1.%'
       OR model_name LIKE 'qwen2.%'
       OR model_name LIKE 'qwen3.%'
       OR model_name LIKE 'qwen4.%'
       OR model_name LIKE 'qwen5.%');
