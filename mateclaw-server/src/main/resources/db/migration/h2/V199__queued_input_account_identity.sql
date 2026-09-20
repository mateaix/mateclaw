-- Preserve authenticated account identity across durable queue replay.
-- Legacy entries deliberately remain unasserted; never infer identity from a display name.
ALTER TABLE mate_conversation_input_queue ADD COLUMN requester_user_id BIGINT NULL;
