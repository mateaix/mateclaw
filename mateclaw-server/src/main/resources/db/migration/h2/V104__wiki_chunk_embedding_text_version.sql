-- V104: track which input format a chunk's stored embedding was generated against.
-- The embedding input builder concatenates raw title / header breadcrumb / page
-- number alongside chunk content; bumping the builder's CURRENT_INPUT_VERSION
-- forces a re-embed pass without changing the model. NULL is treated as the
-- legacy content-only format and re-embedded lazily on the next pass.
ALTER TABLE mate_wiki_chunk ADD COLUMN IF NOT EXISTS embedding_text_version VARCHAR(32) NULL;
