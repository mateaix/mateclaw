-- Add DeepSeek V4 (flash + pro) model entries to mate_model_config for
-- existing deployments. New installs pick these up via DatabaseBootstrapRunner
-- from data-{en,zh,mysql-en,mysql-zh}.sql; this migration covers operators
-- already on V44.
--
-- V4 supports reasoning_effort together with thinking control. NULL
-- temperature/top_p marks the model as thinking-managed
-- (DeepSeekV4ThinkingDecorator handles the per-request thinking field
-- injection).

MERGE INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
KEY (id)
VALUES
(1000000282, 'DeepSeek V4 Flash', 'deepseek', 'deepseek-v4-flash', 'DeepSeek V4 Flash (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
(1000000283, 'DeepSeek V4 Pro', 'deepseek', 'deepseek-v4-pro', 'DeepSeek V4 Pro (1M context, reasoning via thinking-enabled mode)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0);
