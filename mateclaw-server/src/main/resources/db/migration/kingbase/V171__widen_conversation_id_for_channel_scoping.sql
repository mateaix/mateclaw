-- See the H2 file for context. KingbaseES (PostgreSQL-compatible) uses
-- ALTER COLUMN ... TYPE. The NOT NULL and UNIQUE constraints on the column are
-- preserved by a type change.

ALTER TABLE mate_conversation ALTER COLUMN conversation_id TYPE VARCHAR(128);
ALTER TABLE mate_message ALTER COLUMN conversation_id TYPE VARCHAR(128);
