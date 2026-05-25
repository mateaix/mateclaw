-- Output format declared on the template so the executor can validate the
-- LLM's response shape. 'markdown' (default) keeps the legacy behaviour
-- where output is treated as Markdown and saved as page content; 'json'
-- asks the LLM for a single JSON object and the executor parses + validates
-- before persisting. Future formats (table, yaml) can extend this column.

ALTER TABLE mate_wiki_transformation
    ADD COLUMN IF NOT EXISTS output_format VARCHAR(16) NOT NULL DEFAULT 'markdown';
