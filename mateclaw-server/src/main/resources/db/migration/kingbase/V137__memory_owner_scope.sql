-- V137: Per-owner memory isolation with a three-state visibility scope (MySQL).
--
-- See the H2 counterpart for the full rationale. MySQL has no
-- "ADD COLUMN IF NOT EXISTS", so each column/index is guarded with an
-- INFORMATION_SCHEMA existence check + prepared statement for idempotency.
--
-- Existing rows are backfilled to scope='TEAM' by the NOT NULL DEFAULT so that
-- upgrading does NOT hide previously-shared memory.

-- ---------- mate_workspace_file ----------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_workspace_file' AND column_name = 'owner_key'
    ) THEN
        ALTER TABLE mate_workspace_file ADD COLUMN owner_key VARCHAR(128) NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_workspace_file' AND column_name = 'scope'
    ) THEN
        ALTER TABLE mate_workspace_file ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_workspace_file_scope_owner ON mate_workspace_file (agent_id, scope, owner_key);

-- Shared rows use the '' sentinel (not NULL) so the unique index below treats
-- one shared row per filename as a single slot (NULLs are distinct in unique indexes).
UPDATE mate_workspace_file SET owner_key = '' WHERE owner_key IS NULL;

-- De-duplicate before adding the unique index: the table never had a unique
-- constraint and the service layer was check-then-insert, so historical
-- duplicates may exist. Keep the most recently inserted row per
-- (agent_id, filename, owner_key); drop the rest. The extra derived-table wrap
-- is required so MySQL doesn't reject selecting from the table being deleted.
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_workspace_file_owner ON mate_workspace_file (agent_id, filename, owner_key);

-- ---------- mate_memory_recall ----------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_memory_recall' AND column_name = 'owner_key'
    ) THEN
        ALTER TABLE mate_memory_recall ADD COLUMN owner_key VARCHAR(128) NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_memory_recall' AND column_name = 'scope'
    ) THEN
        ALTER TABLE mate_memory_recall ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_memory_recall_scope_owner ON mate_memory_recall (agent_id, scope, owner_key);

-- ---------- mate_fact ----------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_fact' AND column_name = 'owner_key'
    ) THEN
        ALTER TABLE mate_fact ADD COLUMN owner_key VARCHAR(128) NULL;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_fact' AND column_name = 'scope'
    ) THEN
        ALTER TABLE mate_fact ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'TEAM';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_fact_scope_owner ON mate_fact (agent_id, scope, owner_key);
