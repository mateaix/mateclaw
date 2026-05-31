package vip.mate.channel.feishu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the invariant that {@link FeishuChannelAdapter#buildConversationId} produces
 * exactly the id {@code ChannelMessageRouter} derives for the same chat.
 *
 * <p>The recent-file cache saves inbound attachments under
 * {@code data/chat-uploads/{conversationId}/}; the prompt only exposes a file's
 * name (not its path) to the model, so {@code ReadFileTool}/{@code DocumentExtractTool}
 * resolve it through {@code ChatUploadResolver} under the runtime conversationId.
 * If the storage id and the runtime id diverge, those tools cannot find the file
 * and document reads silently fail — which is precisely the regression these tests
 * guard against.
 *
 * <p>The router derives its id from the routed {@code ChannelMessage}, whose
 * {@code chatId} is {@code (isGroup ? shortSuffix : null)} and whose {@code senderId}
 * is the full open id, via {@code feishu:{chatId != null ? chatId : senderId}}.
 */
class FeishuConversationIdAlignmentTest {

    private static final String CHANNEL = FeishuChannelAdapter.CHANNEL_TYPE; // "feishu"
    private static final String SHORT_SUFFIX = "cli2_abcd1234";
    private static final String SENDER = "ou_user0123456789";

    /** Mirror of ChannelMessageRouter#buildConversationId against the routed message. */
    private static String routerConversationId(String shortSuffix, String senderId, boolean isGroup) {
        String routedChatId = isGroup ? shortSuffix : null;
        String identifier = routedChatId != null ? routedChatId : senderId;
        return identifier != null ? CHANNEL + ":" + identifier : null;
    }

    @Test
    void group_usesShortSuffix() {
        assertEquals(CHANNEL + ":" + SHORT_SUFFIX,
                FeishuChannelAdapter.buildConversationId(SHORT_SUFFIX, SENDER, true));
    }

    @Test
    void dm_usesSenderOpenIdAndIgnoresShortSuffix() {
        assertEquals(CHANNEL + ":" + SENDER,
                FeishuChannelAdapter.buildConversationId(SHORT_SUFFIX, SENDER, false));
    }

    @Test
    void group_nullShortSuffix_fallsBackToSender() {
        // Degenerate group path: routed chatId is null, so the router (and this helper)
        // fall back to the sender open id — never null when a sender is present.
        assertEquals(CHANNEL + ":" + SENDER,
                FeishuChannelAdapter.buildConversationId(null, SENDER, true));
    }

    @Test
    void dm_nullSender_returnsNull() {
        assertNull(FeishuChannelAdapter.buildConversationId(SHORT_SUFFIX, null, false));
    }

    @Test
    void matchesRouterFormula_acrossCases() {
        // Group and DM, with and without a short suffix — the storage id must equal
        // the id the router computes for the routed ChannelMessage in every case.
        assertEquals(routerConversationId(SHORT_SUFFIX, SENDER, true),
                FeishuChannelAdapter.buildConversationId(SHORT_SUFFIX, SENDER, true));
        assertEquals(routerConversationId(SHORT_SUFFIX, SENDER, false),
                FeishuChannelAdapter.buildConversationId(SHORT_SUFFIX, SENDER, false));
        assertEquals(routerConversationId(null, SENDER, true),
                FeishuChannelAdapter.buildConversationId(null, SENDER, true));
        assertEquals(routerConversationId(SHORT_SUFFIX, null, false),
                FeishuChannelAdapter.buildConversationId(SHORT_SUFFIX, null, false));
    }
}
