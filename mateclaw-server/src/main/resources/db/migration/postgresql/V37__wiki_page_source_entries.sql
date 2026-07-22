-- RFC-047 P2: Add source_entries column to mate_wiki_page for paired (rawId, rawTitle) lineage.
-- Paired entries guarantee title-rawId alignment even when raw titles change.
-- Dual-written alongside the existing source_raw_ids for backwards compatibility.
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS source_entries TEXT NULL;
