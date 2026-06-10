-- Add Claude Fable 5 model entries to mate_model_config. Unlike earlier Claude
-- families, the Fable rows live ONLY here, not in the data-mysql-{en,zh}.sql
-- seed: Flyway runs every version (V1..) on a fresh database, so the migration
-- seeds new installs and upgrades existing deployments alike. The trade-off is
-- that the description below is English-only (seed files carry localized copy).
--
-- Fable 5 is a reasoning-first model with a 1M-token context window and native
-- vision input. It follows the same strict API contract as Claude 4.7+:
-- temperature / top_p / top_k must be NULL (otherwise HTTP 400), and the
-- "xhigh" adaptive thinking tier is available. Both are handled in
-- AnthropicChatModelBuilder via the isClaudeFable() / isClaude47OrLater()
-- detectors. Vision capability is resolved in ModelCapabilityService.
--
-- INSERT ... ON DUPLICATE KEY UPDATE is the MySQL idempotent upsert.
-- Same V number is used in h2/ for cross-dialect parity.

INSERT INTO mate_model_config (id, name, provider, model_name, description, temperature, max_tokens, top_p, builtin, enabled, is_default, create_time, update_time, deleted)
VALUES
-- Direct Anthropic
(1000000300, 'Claude Fable 5', 'anthropic', 'claude-fable-5', 'Anthropic Claude Fable 5 (1M context, vision, xhigh adaptive thinking)', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- OpenRouter passthrough
(1000000301, 'Claude Fable 5', 'openrouter', 'anthropic/claude-fable-5', 'Claude Fable 5 via OpenRouter', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0),
-- Claude Code OAuth (Pro/Max subscription)
(1000000302, 'Claude Fable 5', 'anthropic-claude-code', 'claude-fable-5', 'Claude Fable 5 via Claude Code Pro/Max subscription', NULL, 4096, NULL, TRUE, TRUE, FALSE, NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    provider = VALUES(provider),
    model_name = VALUES(model_name),
    description = VALUES(description),
    temperature = VALUES(temperature),
    max_tokens = VALUES(max_tokens),
    top_p = VALUES(top_p),
    builtin = VALUES(builtin),
    enabled = VALUES(enabled),
    is_default = VALUES(is_default),
    update_time = VALUES(update_time),
    deleted = VALUES(deleted);
