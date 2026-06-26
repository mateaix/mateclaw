-- Adds the MIME type column to mate_wiki_raw_material so the upload pipeline
-- can route uploads to the right downstream extractor. Image source types
-- in particular need the original Content-Type to pick a vision provider
-- and to render previews correctly without re-sniffing the file.
--
-- KingbaseES (PostgreSQL) supports ADD COLUMN IF NOT EXISTS natively.

ALTER TABLE mate_wiki_raw_material ADD COLUMN IF NOT EXISTS mime_type VARCHAR(64);
