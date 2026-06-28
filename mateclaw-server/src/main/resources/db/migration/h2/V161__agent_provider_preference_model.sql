-- Per-agent preferred *model* chain (provider + model).
--
-- Extends mate_agent_provider_preference from provider-level to
-- (provider, model)-level: a preference entry may now pin a specific chat
-- model, and the SAME provider may appear multiple times with different
-- models (e.g. A/modelX -> A/modelY -> B/modelZ).
--
-- model_id NULL  = use the provider's default chat model (fully backward
--                  compatible with pre-existing provider-only rows).
-- model_id <id>  = matches mate_model_config.id — pin that exact model.
--
-- The unique key moves from (agent_id, provider_id) to
-- (agent_id, provider_id, model_id) so the same provider can repeat. Note
-- NULLs are treated as distinct in a unique index, so duplicate
-- provider-default rows are not DB-enforced; the service replaces the whole
-- list on save and the UI prevents that, so this is intentional.

ALTER TABLE mate_agent_provider_preference ADD COLUMN IF NOT EXISTS model_id BIGINT;
DROP INDEX IF EXISTS uk_agent_provider;
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_provider_model
    ON mate_agent_provider_preference(agent_id, provider_id, model_id);
