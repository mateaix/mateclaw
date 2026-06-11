-- Per-KB source-watcher toggle. Auto-sync (the periodic directory scan) was
-- previously gated only by the server-global `mate.wiki.watcher-enabled`.
-- This column makes the auto-sync opt-in per knowledge base: a KB is scanned
-- automatically only when the global master switch is on AND this flag is set
-- (AND semantics). Manual "scan now" is unaffected by this flag. Defaults to 0
-- so existing KBs are not auto-scanned until explicitly enabled.

ALTER TABLE mate_wiki_knowledge_base
    ADD COLUMN IF NOT EXISTS watcher_enabled TINYINT(1) NOT NULL DEFAULT 0;
