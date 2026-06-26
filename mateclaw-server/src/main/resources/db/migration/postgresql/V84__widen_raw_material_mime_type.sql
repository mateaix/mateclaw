-- V80 created mate_wiki_raw_material.mime_type as VARCHAR(64). Office Open
-- XML Content-Types blow that on the very first upload — docx is 71 chars,
-- pptx is 73, xlsx is 65 — so any user uploading a Word / Excel / PowerPoint
-- file hits "Data truncation: Data too long for column 'mime_type'".
-- Widen to 255 (covers any registered RFC 6838 type with parameters).

ALTER TABLE mate_wiki_raw_material ALTER COLUMN mime_type TYPE VARCHAR(255);
