package vip.mate.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cover the three new {@link ChatOrigin} sender fields (senderName,
 * channelType, chatId), the {@link ChatOrigin#withSender} wither, and
 * the JSON round-trip path that {@code ApprovalReplayContinuityTest}
 * already exercises for the rest of the record.
 *
 * <p>Carry the field-evolution guarantees pinned in {@link ChatOrigin}'s
 * doc: existing fields preserved by every wither, new fields default
 * to null when not supplied (e.g. via {@code web()} / {@code cron()}
 * factories).
 */
class ChatOriginSenderFieldsTest {

    @Test
    @DisplayName("withSender returns a new instance with the three fields populated")
    void withSenderUpdatesFields() {
        ChatOrigin base = ChatOrigin.EMPTY;
        ChatOrigin enriched = base.withSender("Alice", "feishu", "oc_42");

        assertEquals("Alice", enriched.senderName());
        assertEquals("feishu", enriched.channelType());
        assertEquals("oc_42", enriched.chatId());

        // Original untouched (record immutability + wither contract)
        assertNull(base.senderName());
        assertNull(base.channelType());
        assertNull(base.chatId());
    }

    @Test
    @DisplayName("withSender preserves every pre-existing field")
    void withSenderPreservesOtherFields() {
        ChatOrigin original = new ChatOrigin(
                7L, "conv-1", "u123", 5L, "/ws", 9L, null, false,
                null, null, null);
        ChatOrigin enriched = original.withSender("Alice", "wecom", "g-1");

        // All non-sender fields unchanged
        assertEquals(original.agentId(), enriched.agentId());
        assertEquals(original.conversationId(), enriched.conversationId());
        assertEquals(original.requesterId(), enriched.requesterId());
        assertEquals(original.workspaceId(), enriched.workspaceId());
        assertEquals(original.workspaceBasePath(), enriched.workspaceBasePath());
        assertEquals(original.channelId(), enriched.channelId());
        assertEquals(original.channelTarget(), enriched.channelTarget());
        assertEquals(original.cronOrigin(), enriched.cronOrigin());
    }

    @Test
    @DisplayName("web() factory sets channelType to 'web' and leaves sender / chat null")
    void webFactoryDefaults() {
        ChatOrigin web = ChatOrigin.web("conv_1", "user-1", 5L, "/ws");
        assertEquals("web", web.channelType());
        assertNull(web.senderName());
        assertNull(web.chatId());
    }

    @Test
    @DisplayName("cron() factory leaves all three sender fields null")
    void cronFactoryDefaults() {
        ChatOrigin cron = ChatOrigin.cron("cron_1", 1L, null, 9L, null);
        assertNull(cron.senderName());
        assertNull(cron.channelType());
        assertNull(cron.chatId());
    }

    @Test
    @DisplayName("JSON round-trip preserves the new sender fields")
    void jsonRoundTripPreservesFields() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ChatOrigin origin = new ChatOrigin(
                7L, "feishu:oc_42", "ou_xyz", 5L, "/data/ws/5",
                9L, null, false,
                "Alice", "feishu", "oc_42");

        String json = om.writeValueAsString(origin);
        ChatOrigin restored = om.readValue(json, ChatOrigin.class);

        assertEquals(origin, restored);
        assertEquals("Alice", restored.senderName());
        assertEquals("feishu", restored.channelType());
        assertEquals("oc_42", restored.chatId());
    }

    @Test
    @DisplayName("withAgent / withWorkspace / withConversationId preserve sender fields")
    void existingWithersPreserveSenderFields() {
        ChatOrigin origin = ChatOrigin.EMPTY.withSender("Alice", "feishu", "oc_42");

        assertEquals("Alice", origin.withAgent(99L).senderName());
        assertEquals("feishu", origin.withWorkspace(7L, "/ws").channelType());
        assertEquals("oc_42", origin.withConversationId("new").chatId());
    }
}
