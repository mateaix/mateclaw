-- See the H2 file for context. MySQL widens with MODIFY COLUMN; the full column
-- definition is restated so NOT NULL is preserved (MODIFY replaces the whole
-- definition). Widening the type keeps the existing UNIQUE index on
-- mate_conversation.conversation_id. Widening is idempotent enough that Flyway's
-- version tracking is the only re-run guard needed (no ADD COLUMN existence check).

ALTER TABLE mate_conversation MODIFY COLUMN conversation_id VARCHAR(128) NOT NULL;
ALTER TABLE mate_message MODIFY COLUMN conversation_id VARCHAR(128) NOT NULL;
