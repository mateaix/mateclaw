package vip.mate.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-063r §2.1: ChatOrigin value-object invariants.
 */
class ChatOriginTest {

    @Test
    void from_nullToolContext_returnsEmpty() {
        assertSame(ChatOrigin.EMPTY, ChatOrigin.from(null));
    }

    @Test
    void from_toolContextWithoutOrigin_returnsEmpty() {
        ToolContext ctx = new ToolContext(Map.of("unrelated.key", "x"));
        assertSame(ChatOrigin.EMPTY, ChatOrigin.from(ctx));
    }

    @Test
    void roundTripThroughToolContext_preservesAllFields() {
        ChannelTarget target = new ChannelTarget("user-42", "thread-abc", "bot-001");
        ChatOrigin original = new ChatOrigin(7L, "wechat:42", "u123", 5L,
                "/data/ws/5", 9L, target, false, null, null, null);

        ToolContext ctx = original.toToolContext();
        ChatOrigin restored = ChatOrigin.from(ctx);

        assertEquals(original, restored);
    }

    @Test
    void wither_doesNotMutateOriginal() {
        ChatOrigin base = ChatOrigin.cron("cron_1", 5L, "/data/ws/5", 9L,
                new ChannelTarget("group-a", null, null));
        ChatOrigin enriched = base.withAgent(42L);

        assertNull(base.agentId(), "withAgent must not mutate the original");
        assertEquals(42L, enriched.agentId());
        assertEquals(base.channelId(), enriched.channelId(), "channelId must be preserved");
        assertEquals(base.channelTarget(), enriched.channelTarget(),
                "channelTarget must be preserved");
    }

    @Test
    void cronFactory_setsRequesterToSystem() {
        ChatOrigin origin = ChatOrigin.cron("cron_7", 1L, null, 3L, null);
        assertEquals("system", origin.requesterId());
        assertNull(origin.agentId(), "agentId is enriched later by BaseAgent");
    }

    @Test
    void cronOriginFlag_setByFactoryAndPreservedByWithers() {
        ChatOrigin cron = ChatOrigin.cron("cron_7", 1L, null, 3L, null);
        assertTrue(cron.cronOrigin(), "cron() factory must flag the origin as a cron run");
        assertTrue(cron.withAgent(9L).cronOrigin(), "withAgent must preserve cronOrigin");
        assertTrue(cron.withConversationId("tasks_1").cronOrigin(),
                "withConversationId must preserve cronOrigin");
        assertTrue(cron.withWorkspace(2L, "/ws").cronOrigin(),
                "withWorkspace must preserve cronOrigin");

        assertFalse(ChatOrigin.web("conv_1", "u1", 1L, null).cronOrigin(),
                "web() origin must not be flagged as a cron run");
        assertFalse(ChatOrigin.EMPTY.cronOrigin(), "EMPTY must not be flagged as a cron run");
    }

    @Test
    void jsonSerialization_isStableAndForwardCompatible() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ChatOrigin origin = new ChatOrigin(7L, "wechat:42", "u123", 5L,
                "/data/ws/5", 9L, new ChannelTarget("user-42", "thread-abc", "bot-001"), false, null, null, null);

        String json = om.writeValueAsString(origin);
        ChatOrigin restored = om.readValue(json, ChatOrigin.class);

        assertEquals(origin, restored);

        // RFC-063r §2.1 forward compatibility: future-added unknown fields
        // must not break deserialization (covers approval rows surviving upgrades).
        String jsonWithExtraField = json.replaceFirst("\\}$", ",\"futureField\":\"x\"}");
        ChatOrigin tolerated = om.readValue(jsonWithExtraField, ChatOrigin.class);
        assertEquals(origin, tolerated);
    }
}
