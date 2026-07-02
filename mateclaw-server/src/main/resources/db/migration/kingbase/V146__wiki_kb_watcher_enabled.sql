-- Per-KB source-watcher toggle. See the h2 sibling for the prose explanation.
-- MySQL INFORMATION_SCHEMA guard converted to plpgsql DO block.
-- SMALLINT (not BOOLEAN): WikiKnowledgeBaseEntity.watcherEnabled is Integer (1/0).
-- Vanilla PostgreSQL cannot map a BOOLEAN into a JDBC int. Do not switch to BOOLEAN.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'mate_wiki_knowledge_base' AND column_name = 'watcher_enabled'
    ) THEN
        ALTER TABLE mate_wiki_knowledge_base ADD COLUMN watcher_enabled SMALLINT NOT NULL DEFAULT 0;
    END IF;
END $$;
