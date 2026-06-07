-- V141: Per-agent knowledge base access scope for wiki tools.
--
-- Knowledge bases are workspace-shared, so by default every agent in a
-- workspace can reach every KB in it. This table lets an operator pin an
-- agent to a subset of KBs: once at least one enabled row exists for an
-- agent, the wiki tools (list/search/read/write) can only see and target
-- those KBs. No rows for an agent = unrestricted (workspace-wide), which
-- keeps every pre-existing agent behaving exactly as before.
--
-- The default KB an agent's wiki tools fall back to when no kbId/kbName is
-- given still lives on mate_agent.primary_kb_id; this table only narrows the
-- visible set, and the primary is expected to be one of the scoped KBs.

CREATE TABLE IF NOT EXISTS mate_agent_wiki_kb (
    id          BIGINT    NOT NULL PRIMARY KEY,
    agent_id    BIGINT    NOT NULL,
    kb_id       BIGINT    NOT NULL,
    enabled     TINYINT   NOT NULL DEFAULT 1,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_wiki_kb
    ON mate_agent_wiki_kb (agent_id, kb_id, deleted);
CREATE INDEX IF NOT EXISTS idx_agent_wiki_kb_agent
    ON mate_agent_wiki_kb (agent_id, deleted);
