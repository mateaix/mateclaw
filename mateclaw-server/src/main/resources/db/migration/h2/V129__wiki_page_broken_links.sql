-- V129: Persisted wikilink lint state.
--
-- broken_links               JSON array of unresolved outlink targets for THIS
--                            page. Derived from outgoing_links minus the active
--                            KB slug set (case-insensitive). Empty array means
--                            "scanned, all targets resolve"; NULL means
--                            "never scanned". Kept separate from outgoing_links
--                            (which records every [[...]] target written into
--                            content, hit-or-miss) so backlinks / direct-link
--                            signals are unaffected.
-- broken_links_scanned_at    Timestamp of the most recent broken_links
--                            recompute. UI surfaces this as "last scan" so
--                            staleness is visible. Reset whenever the page is
--                            re-saved (broken_links is recomputed in the same
--                            transaction).
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS broken_links TEXT DEFAULT NULL;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS broken_links_scanned_at TIMESTAMP DEFAULT NULL;
