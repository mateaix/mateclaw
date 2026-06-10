package vip.mate.channel;

import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChannelTarget;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.channel.model.ChannelEntity;

/**
 * RFC-063r §2.2: factory that translates an inbound channel message into a
 * {@link ChatOrigin}. Lives in {@code vip.mate.channel} (not in
 * {@code vip.mate.agent.context}) so that the dependency direction stays
 * {@code channel → agent} and never the reverse.
 */
@Component
public class ChannelChatOriginFactory {

    /**
     * Build a {@link ChatOrigin} for a channel-originated message.
     *
     * @param channel             channel entity (non-null) — provides id + workspaceId
     * @param message             inbound message (non-null) — provides senderId + reply target
     * @param conversationId      resolved conversation id (channel-scoped)
     * @param workspaceBasePath   workspace activity directory; null = unrestricted
     */
    public ChatOrigin from(ChannelEntity channel,
                           ChannelMessage message,
                           String conversationId,
                           String workspaceBasePath) {
        ChannelTarget target = new ChannelTarget(
                resolveTargetId(message),
                /* threadId  */ null,    // adapters fill via ChannelMessage extension fields when available
                /* accountId */ null);
        return new ChatOrigin(
                /* agentId           */ null,
                /* conversationId    */ conversationId,
                /* requesterId       */ message.getSenderId(),
                /* workspaceId       */ channel.getWorkspaceId(),
                /* workspaceBasePath */ workspaceBasePath,
                /* channelId         */ channel.getId(),
                /* channelTarget     */ target,
                /* cronOrigin        */ false,
                /* senderName        */ message.getSenderName(),
                /* channelType       */ message.getChannelType() != null
                                            ? message.getChannelType()
                                            : channel.getChannelType(),
                /* chatId            */ message.getChatId(),
                /* baseUrl           */ null);   // IM origins have no request host; rely on public-base-url config
    }

    /**
     * Resolve the IM target id used for proactive sends.
     *
     * <p><b>Critical</b>: must NOT use {@link ChannelMessage#getReplyToken()} —
     * for DingTalk the reply token encodes a {@code sessionWebhook} URL that
     * expires ~90 minutes after the inbound message. A cron persisted with an
     * expired sessionWebhook fails proactive delivery with 401/403 forever
     * after the window lapses.
     *
     * <p>Resolution order — both fields are stable identifiers across all
     * supported channels:
     * <ol>
     *   <li>{@code chatId} — group / channel / room identifier; preferred so
     *       cron messages land in the same conversation the user triggered
     *       the cron from</li>
     *   <li>{@code senderId} — user identifier; fallback for private chats
     *       where {@code chatId} is null. DingTalk's {@code proactiveSend}
     *       routes a userId through the Robot API ({@code oToMessages/batchSend}),
     *       which works indefinitely.</li>
     * </ol>
     */
    private String resolveTargetId(ChannelMessage message) {
        if (message.getChatId() != null && !message.getChatId().isBlank()) {
            return message.getChatId();
        }
        return message.getSenderId();
    }
}
