-- Page-level embedding so synthesis pages produced by transformations can be
-- surfaced by semantic search even when their generated content doesn't
-- appear in the source raw's chunks. The retriever combines chunk-level
-- cosine (via sourceRawIds) with these page-level vectors taking the max,
-- so a synthesis page that the LLM authored with vocabulary not present in
-- the original PDF can still match a user's natural-language query.

ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS embedding BLOB DEFAULT NULL;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(64) DEFAULT NULL;
ALTER TABLE mate_wiki_page ADD COLUMN IF NOT EXISTS embedding_text_version VARCHAR(32) DEFAULT NULL;
