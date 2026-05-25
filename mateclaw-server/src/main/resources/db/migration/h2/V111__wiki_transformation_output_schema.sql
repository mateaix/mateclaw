-- Optional JSON Schema describing the shape the LLM should produce when
-- output_format='json'. The executor injects the schema into the prompt
-- so the model has explicit field/type expectations, and validates the
-- parsed JSON against a lightweight required-fields check after parsing.
-- Stored as TEXT — the schema can be arbitrary JSON Schema text.

ALTER TABLE mate_wiki_transformation ADD COLUMN IF NOT EXISTS output_schema CLOB DEFAULT NULL;
