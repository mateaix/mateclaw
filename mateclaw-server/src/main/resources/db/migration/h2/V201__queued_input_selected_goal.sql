-- Snapshot the selected managed Goal when an authenticated Web follow-up is queued.
-- NULL is an old, unknown selection; 0 explicitly means no managed Goal was selected.
ALTER TABLE mate_conversation_input_queue ADD COLUMN selected_goal_id BIGINT NULL;
