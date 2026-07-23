-- V171: widen conversation_id to hold the channel-scoped id format
-- (H2 dialect). Channel conversation ids now carry a channelId segment
-- ({channelType}:{channelId}:{sender|chat}), which can exceed the old
-- VARCHAR(64). Widen the two strongly-bound tables to VARCHAR(128), matching
-- mate_channel_session / audit tables. The UNIQUE index on
-- mate_conversation.conversation_id is preserved by the type change.

ALTER TABLE mate_conversation ALTER COLUMN conversation_id SET DATA TYPE VARCHAR(128);
ALTER TABLE mate_message ALTER COLUMN conversation_id SET DATA TYPE VARCHAR(128);
