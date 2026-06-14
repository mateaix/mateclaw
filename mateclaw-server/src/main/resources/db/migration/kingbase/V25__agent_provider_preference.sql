-- RFC-009 Phase 4 PR-3: per-agent provider preferences
--
-- Lets each agent declare an ordered list of preferred provider ids. Empty
-- table for an agent (no rows) means "use the global fallback chain order"
-- — fully backwards compatible with pre-PR-3 behavior. When rows exist,
-- listed providers are tried in ascending sort_order before any non-listed
-- provider is considered.
--
-- This is purely a routing hint. The runtime walker still gates each entry
-- through AvailableProviderPool / ProviderHealthTracker — a preferred
-- provider that is HARD-removed or in cooldown is still skipped.

CREATE TABLE IF NOT EXISTS mate_agent_provider_preference (
    id           BIGINT       NOT NULL PRIMARY KEY,
    agent_id     BIGINT       NOT NULL,
    provider_id  VARCHAR(128) NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    enabled      SMALLINT   NOT NULL DEFAULT 1,
    create_time  TIMESTAMP     NOT NULL,
    update_time  TIMESTAMP     NOT NULL,
    deleted      INT          NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_provider ON mate_agent_provider_preference (agent_id, provider_id);
CREATE INDEX IF NOT EXISTS idx_agent_provider_order ON mate_agent_provider_preference (agent_id, sort_order);
