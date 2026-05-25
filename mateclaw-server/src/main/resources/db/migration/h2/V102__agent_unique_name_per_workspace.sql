-- Enforce unique Agent name within a workspace.
--
-- Before V102 the application allowed two Agents with the same name in the
-- same workspace, which made name-based routing (e.g. @-mention an Agent in
-- an IM channel) ambiguous and let an attacker shadow an existing Agent.
--
-- Step 1 — rename pre-existing duplicates so the new index can be created
-- without an offline migration. The oldest row per (workspace_id, name)
-- keeps the original name; later rows are renamed to a fully synthetic
-- migration tag.
--
-- The rename target intentionally drops the original name and substitutes
-- `__mate_dup_v102__<id>__<uuid>`. Any deterministic transformation of
-- the original name has a non-zero collision risk against a hand-typed
-- pre-existing row that happens to match the pattern (e.g. someone named
-- their agent `foo__v102_dup__2`). A random UUID component drives the
-- collision probability to ~1/2^122, low enough to call "provably unique"
-- for a one-shot admin migration. The original name is recoverable via
-- the audit log; the row id stays embedded in the new name for traceability.
UPDATE mate_agent
SET name = CONCAT('__mate_dup_v102__', id, '__', RANDOM_UUID())
WHERE id IN (
    SELECT a.id FROM mate_agent a
    WHERE EXISTS (
        SELECT 1 FROM mate_agent b
        WHERE b.workspace_id = a.workspace_id
          AND b.name = a.name
          AND b.id < a.id
    )
);

-- Step 2 — DB-level guarantee. Service layer also pre-checks for friendly
-- 409 messages; this index is the racy-write safety net.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_workspace_name
    ON mate_agent(workspace_id, name);
