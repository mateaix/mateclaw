package vip.mate.channel.wecom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pin the alignment between {@code WeComChannelAdapter.inboundConversationId}
 * and {@code ChannelMessageRouter.buildConversationId}.
 *
 * <p>These two compute the same logical conversation id from different code
 * paths: the adapter pre-computes it to choose the per-conversation
 * upload directory <em>before</em> the {@link vip.mate.channel.ChannelMessage}
 * exists, and the router computes it from the {@code ChannelMessage}
 * downstream. They MUST agree on the same string format, otherwise
 * inbound media saves to one directory while messages persist under a
 * different conversationId — and the {@code /api/v1/chat/files/{convId}/...}
 * endpoint's owner check fails for every fetch (403 → broken images).
 *
 * <p>The format both produce: {@code wecom:{channelId}:{chatId}} for groups,
 * {@code wecom:{channelId}:{senderId}} for 1:1 — no {@code group:} infix. The
 * {@code channelId} segment scopes the id to one channel row (hence one
 * workspace) so the same sender on two workspaces' wecom channels never
 * collides into one conversation.
 */
class WeComInboundConversationIdTest {

    private static final Long CHANNEL_ID = 2056987497408438273L;

    private static String inboundConversationId(String senderId, String chatId, String chatType) throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "inboundConversationId", String.class, String.class, String.class, Long.class);
        m.setAccessible(true);
        return (String) m.invoke(null, senderId, chatId, chatType, CHANNEL_ID);
    }

    /** Mirror of ChannelMessageRouter#buildConversationId for cross-checking. */
    private static String routerConversationId(String identifier) {
        return "wecom:" + CHANNEL_ID + ":" + identifier;
    }

    @Test
    @DisplayName("group → wecom:{channelId}:{chatId} (no 'group:' infix, matches router)")
    void groupChatIdFormat() throws Exception {
        // The channelId segment scopes the id to one channel/workspace; the
        // group branch still uses chatId (no "group:" infix) to match the router.
        assertEquals("wecom:" + CHANNEL_ID + ":group-abc",
                inboundConversationId("XuZhanFu", "group-abc", "group"));
    }

    @Test
    @DisplayName("1:1 → wecom:{channelId}:{senderId} (chatId is irrelevant in single chats)")
    void singleChatSenderFormat() throws Exception {
        // Single-chat case: both adapter and router fall back to senderId; pin it
        // so a future refactor of either side doesn't accidentally diverge.
        assertEquals("wecom:" + CHANNEL_ID + ":XuZhanFu",
                inboundConversationId("XuZhanFu", null, "single"));
        assertEquals("wecom:" + CHANNEL_ID + ":XuZhanFu",
                inboundConversationId("XuZhanFu", "ignored-when-single", "single"));
    }

    @Test
    @DisplayName("matches ChannelMessageRouter.buildConversationId for both group and 1:1")
    void matchesRouterFormat() throws Exception {
        // Router's identifier picker:
        //   chatId != null  → "{channelType}:{channelId}:{chatId}"   (group)
        //   chatId == null  → "{channelType}:{channelId}:{senderId}" (single)
        // Inbound side passes chatId for groups, null/ignored for 1:1.
        // Both must arrive at the same string, exact-equal.
        assertEquals(routerConversationId("group-xyz"),
                inboundConversationId("Alice", "group-xyz", "group"));
        assertEquals(routerConversationId("Alice"),
                inboundConversationId("Alice", null, "single"));
    }
}
