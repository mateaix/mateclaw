-- V192: make owner-aware recall writes race-safe.
-- Irreversible cleanup: soft-deleted rows are no longer useful to the recall
-- ledger, and duplicate active identities must collapse before uniqueness.
DELETE FROM mate_memory_recall WHERE deleted <> 0;
UPDATE mate_memory_recall SET owner_key = '' WHERE owner_key IS NULL;
UPDATE mate_memory_recall AS target
SET recall_count = (SELECT SUM(COALESCE(source.recall_count, 0))
                    FROM mate_memory_recall AS source
                    WHERE source.agent_id = target.agent_id
                      AND source.filename = target.filename
                      AND source.scope = target.scope
                      AND source.owner_key = target.owner_key),
    daily_count = (SELECT SUM(COALESCE(source.daily_count, 0))
                   FROM mate_memory_recall AS source
                   WHERE source.agent_id = target.agent_id
                     AND source.filename = target.filename
                     AND source.scope = target.scope
                     AND source.owner_key = target.owner_key),
    last_recalled_at = (SELECT MAX(source.last_recalled_at)
                        FROM mate_memory_recall AS source
                        WHERE source.agent_id = target.agent_id
                          AND source.filename = target.filename
                          AND source.scope = target.scope
                          AND source.owner_key = target.owner_key)
WHERE target.id IN (
    SELECT MAX(id) FROM mate_memory_recall
    GROUP BY agent_id, filename, scope, owner_key
    HAVING COUNT(*) > 1
);
DELETE FROM mate_memory_recall
WHERE id NOT IN (
    SELECT MAX(id)
    FROM mate_memory_recall
    GROUP BY agent_id, filename, scope, owner_key
);
ALTER TABLE mate_memory_recall ALTER COLUMN owner_key SET DEFAULT '';
ALTER TABLE mate_memory_recall ALTER COLUMN owner_key SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_memory_recall_identity
    ON mate_memory_recall(agent_id, filename, scope, owner_key);
