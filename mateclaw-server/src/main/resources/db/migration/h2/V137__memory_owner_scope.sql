-- V137: Per-owner memory isolation with a three-state visibility scope.
--
-- Adds owner_key + scope to the three memory-bearing tables so a single agent
-- shared across multiple end-users (web users, IM senders, third-party API
-- end-users) keeps each owner's memory separate.
--
--   owner_key  - the memory subject this row belongs to, as a prefixed string
--                ("user:42", "feishu:ou_xxx", "api:<endUserId>"). NULL means
--                "not owner-scoped" (legacy / shared config rows).
--   scope      - PERSONAL (only the matching owner_key sees it),
--                TEAM      (everyone using the agent sees it),
--                GLOBAL    (always visible).
--
-- Existing rows are backfilled to scope='TEAM' by the NOT NULL DEFAULT so that
-- upgrading does NOT hide previously-shared memory. New memory writes set
-- scope='PERSONAL' with the resolved owner_key; agent config files (AGENTS.md,
-- SOUL.md, PROFILE.md) keep the TEAM default and stay shared.

ALTER TABLE mate_workspace_file ADD COLUMN IF NOT EXISTS owner_key VARCHAR(128) NULL;
ALTER TABLE mate_workspace_file ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
CREATE INDEX IF NOT EXISTS idx_workspace_file_scope_owner ON mate_workspace_file(agent_id, scope, owner_key);
-- Shared rows use the '' sentinel (not NULL) so the unique index below treats
-- one shared row per filename as a single slot.
UPDATE mate_workspace_file SET owner_key = '' WHERE owner_key IS NULL;
-- De-duplicate before adding the unique index: the table never had a unique
-- constraint and the service layer was check-then-insert, so historical
-- duplicates may exist. Keep the most recently inserted row per
-- (agent_id, filename, owner_key); drop the rest so the index can be created.
--
-- IRREVERSIBLE: this keeps MAX(id) (newest row) and PERMANENTLY deletes the
-- other rows in a duplicate group — their content / enabled / sort_order are
-- not preserved or merged. Duplicates are NOT expected (every write path is
-- check-then-insert), so this is a safety net to guarantee the index builds,
-- not a routine merge. If a deployment knowingly relies on duplicate rows,
-- reconcile them manually before upgrading.
DELETE FROM mate_workspace_file
WHERE id NOT IN (
    SELECT keep_id FROM (
        SELECT MAX(id) AS keep_id
        FROM mate_workspace_file
        GROUP BY agent_id, filename, owner_key
    ) t
);
-- One row per (agent, filename, owner): one shared row + one row per PERSONAL
-- owner. Hardens the check-then-insert in saveFile/saveMemoryFile against
-- concurrent / multi-node duplicates.
CREATE UNIQUE INDEX IF NOT EXISTS uk_workspace_file_owner ON mate_workspace_file(agent_id, filename, owner_key);

ALTER TABLE mate_memory_recall ADD COLUMN IF NOT EXISTS owner_key VARCHAR(128) NULL;
ALTER TABLE mate_memory_recall ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
CREATE INDEX IF NOT EXISTS idx_memory_recall_scope_owner ON mate_memory_recall(agent_id, scope, owner_key);

ALTER TABLE mate_fact ADD COLUMN IF NOT EXISTS owner_key VARCHAR(128) NULL;
ALTER TABLE mate_fact ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
CREATE INDEX IF NOT EXISTS idx_fact_scope_owner ON mate_fact(agent_id, scope, owner_key);
