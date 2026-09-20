-- Preserve existing UTF-8 rows; binary attachments use base64 in the canonical content column.
ALTER TABLE mate_skill_file ADD COLUMN content_encoding VARCHAR(16) DEFAULT 'utf8' NOT NULL;
