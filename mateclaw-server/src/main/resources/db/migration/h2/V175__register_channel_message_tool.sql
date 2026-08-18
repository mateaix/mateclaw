-- V175: Register the channel message push bean as a built-in tool so it shows
-- up in the tool picker and can be bound per-agent. The agent runtime already
-- discovers the @Tool bean live (auto-available even without a row), but the
-- picker / per-agent binding validation reads mate_tool — without this row
-- operators cannot grant proactive channel push to agents that use an explicit
-- tool allowlist. One row for the bean: the alias index resolves the class
-- simple name to both @Tool methods (list_channel_sessions,
-- send_channel_message), so binding 'ChannelMessageTool' grants the
-- discover-then-push workflow as one capability.
-- Idempotent: MERGE INTO updates the row when the id already matches.

MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
KEY (id)
VALUES (1000000028, 'ChannelMessageTool', 'Channel Message Push', 'Proactively push one-way messages to IM channel conversations (WeChat Work / DingTalk / Feishu / Telegram / Discord / QQ / Slack). list_channel_sessions discovers pushable conversations; send_channel_message delivers — for alerts, reminders, and async task results.', 'builtin', 'channelMessageTool', '📤', TRUE, TRUE, NOW(), NOW(), 0);
