package vip.mate.tool.builtin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.channel.ChannelAdapter;
import vip.mate.channel.ChannelManager;
import vip.mate.channel.ChannelSessionStore;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.model.ChannelSessionEntity;
import vip.mate.channel.service.ChannelService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Proactive one-way message push to an IM channel conversation.
 *
 * <p>Two-step workflow mirroring the {@code channel_message} skill:
 * {@link #list_channel_sessions} discovers which conversations the bot can
 * push to (a conversation becomes pushable once the bot has received at
 * least one inbound message in it — that inbound event is what populates
 * {@code mate_channel_session} with the platform delivery handle), then
 * {@link #send_channel_message} delivers through the same
 * {@link ChannelManager#sendToChannel} outbound entry the cron delivery
 * pipeline uses.
 *
 * <p>Sessions are scoped to the caller's workspace: only sessions whose
 * bound channel belongs to the {@link ChatOrigin} workspace are listed or
 * accepted as send targets, so an agent cannot push into another
 * workspace's conversations.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelMessageTool {

    /** Keep pushed messages within the same bound the cron channel renderer uses. */
    private static final int MAX_MESSAGE_LENGTH = 4096;

    /** Cap the session listing so a busy install doesn't flood the model context. */
    private static final int MAX_LISTED_SESSIONS = 30;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ChannelSessionStore channelSessionStore;
    private final ChannelManager channelManager;
    private final ChannelService channelService;

    @Tool(description = """
            List IM channel conversations this bot can proactively push messages to \
            (WeChat Work / DingTalk / Feishu / Telegram / Discord / QQ / Slack ...). \
            Call this FIRST to discover the target conversation_id before using \
            send_channel_message — never guess a conversation_id. Only conversations \
            where the bot has previously received a message are pushable. \
            Optionally filter by channel type (e.g. "wecom", "feishu", "dingtalk").""")
    public String list_channel_sessions(
            @ToolParam(required = false,
                    description = "Optional channel type filter: wecom / dingtalk / feishu / telegram / discord / qq / slack / weixin")
            String channelType,
            @Nullable ToolContext ctx) {

        Map<Long, ChannelEntity> channels = workspaceChannels(ctx);
        if (channels.isEmpty()) {
            return "No IM channels are configured in this workspace, so there are no conversations to push to.";
        }

        List<ChannelSessionEntity> sessions = channels.keySet().stream()
                .flatMap(id -> channelSessionStore.listByChannelId(id).stream())
                .filter(s -> channelType == null || channelType.isBlank()
                        || channelType.trim().equalsIgnoreCase(s.getChannelType()))
                .filter(s -> supportsProactive(s.getChannelId()))
                .sorted(Comparator.comparing(ChannelSessionEntity::getLastActiveTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_LISTED_SESSIONS)
                .toList();

        if (sessions.isEmpty()) {
            return "No pushable conversations found"
                    + (channelType != null && !channelType.isBlank() ? " for channel type '" + channelType + "'" : "")
                    + ". A conversation becomes pushable only after the bot has received at least one message in it.";
        }

        StringBuilder sb = new StringBuilder("Pushable conversations (most recently active first):\n");
        for (ChannelSessionEntity s : sessions) {
            ChannelEntity channel = channels.get(s.getChannelId());
            sb.append("- conversation_id: ").append(s.getConversationId())
              .append(" | channel: ").append(channel != null ? channel.getName() : "#" + s.getChannelId())
              .append(" (").append(s.getChannelType()).append(")");
            if (s.getSenderName() != null && !s.getSenderName().isBlank()) {
                sb.append(" | user: ").append(s.getSenderName());
            }
            LocalDateTime lastActive = s.getLastActiveTime();
            if (lastActive != null) {
                sb.append(" | last_active: ").append(TIME_FORMAT.format(lastActive));
            }
            sb.append('\n');
        }
        sb.append("\nUse send_channel_message with the conversation_id to push a message. "
                + "When several conversations match, prefer the most recently active one.");
        return sb.toString();
    }

    @Tool(description = """
            Proactively push a one-way message to an IM channel conversation \
            (WeChat Work / DingTalk / Feishu / Telegram / Discord / QQ / Slack ...). \
            Use ONLY when the task explicitly requires notifying a channel conversation \
            (alerts, reminders, async results) — replying to the current conversation \
            does NOT need this tool. Get the conversation_id from list_channel_sessions \
            first; never guess it. This is a one-way push: no reply comes back.""")
    public String send_channel_message(
            @ToolParam(description = "Target conversation_id exactly as returned by list_channel_sessions")
            String conversationId,
            @ToolParam(description = "Message text to push (plain text / markdown, depending on the channel)")
            String message,
            @Nullable ToolContext ctx) {

        if (conversationId == null || conversationId.isBlank()) {
            return "[Error] conversation_id is required. Call list_channel_sessions first to find the target.";
        }
        if (message == null || message.isBlank()) {
            return "[Error] message is required.";
        }

        ChannelSessionEntity session = channelSessionStore.getSession(conversationId.trim());
        if (session == null) {
            return "[Error] Unknown conversation_id: " + conversationId
                    + ". Call list_channel_sessions to see the valid targets.";
        }
        if (session.getChannelId() == null) {
            return "[Error] Conversation " + conversationId
                    + " has no bound channel and cannot receive proactive messages.";
        }

        // Workspace boundary: the session's channel must belong to the caller's
        // workspace, so an agent cannot push into another workspace's chats.
        Map<Long, ChannelEntity> channels = workspaceChannels(ctx);
        ChannelEntity channel = channels.get(session.getChannelId());
        if (channel == null) {
            return "[Error] Conversation " + conversationId + " does not belong to this workspace.";
        }

        ChannelAdapter adapter = channelManager.getAdapter(session.getChannelId()).orElse(null);
        if (adapter == null) {
            return "[Error] Channel '" + channel.getName() + "' is not running — enable it first.";
        }
        if (!adapter.supportsProactiveSend()) {
            return "[Error] Channel '" + channel.getName() + "' (" + adapter.getChannelType()
                    + ") does not support proactive push.";
        }

        String content = message.length() <= MAX_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_MESSAGE_LENGTH);
        try {
            channelManager.sendToChannel(session.getChannelId(), session.getTargetId(), content);
            log.info("send_channel_message: pushed {} chars to {} via channel {}",
                    content.length(), conversationId, channel.getName());
            return "Message sent to " + conversationId + " via channel '" + channel.getName()
                    + "' (" + session.getChannelType() + ")."
                    + (message.length() > MAX_MESSAGE_LENGTH
                        ? " Note: message was truncated to " + MAX_MESSAGE_LENGTH + " chars." : "");
        } catch (Exception e) {
            log.warn("send_channel_message failed: conversation={}, channel={}, error={}",
                    conversationId, session.getChannelId(), e.getMessage());
            return "[Error] Push failed: " + e.getMessage();
        }
    }

    /**
     * Channels visible to the calling agent, keyed by id. Scoped by the
     * {@link ChatOrigin} workspace; origins without a workspace (legacy
     * callers) fall back to the default workspace.
     */
    private Map<Long, ChannelEntity> workspaceChannels(@Nullable ToolContext ctx) {
        ChatOrigin origin = ChatOrigin.from(ctx);
        Long workspaceId = origin != null && origin.workspaceId() != null ? origin.workspaceId() : 1L;
        return channelService.listChannelsByWorkspace(workspaceId).stream()
                .collect(Collectors.toMap(ChannelEntity::getId, Function.identity(), (a, b) -> a));
    }

    private boolean supportsProactive(Long channelId) {
        if (channelId == null) {
            return false;
        }
        return channelManager.getAdapter(channelId)
                .map(ChannelAdapter::supportsProactiveSend)
                .orElse(false);
    }
}
