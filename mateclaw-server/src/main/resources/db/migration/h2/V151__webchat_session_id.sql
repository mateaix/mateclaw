-- WebChat per-thread sessionId, persisted so it can be recovered even when the
-- conversationId hashes (visitorId + sessionId > 64 chars folds into a hash,
-- which is otherwise unrecoverable — making the thread invisible/unaddressable
-- in the visitor's /sessions listing). NULL for non-webchat rows and for a
-- visitor's default (no-session) thread.
ALTER TABLE mate_conversation ADD COLUMN IF NOT EXISTS webchat_session_id VARCHAR(64);
