package vip.mate.cron.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.context.ChannelTarget;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-063r §2.9: DeliveryConfig must round-trip through Jackson cleanly so
 * MyBatis Plus JacksonTypeHandler can persist + restore it on
 * {@code mate_cron_job.delivery_config}.
 */
class DeliveryConfigTest {

    @Test
    void from_nullChannelTarget_returnsNull() {
        assertNull(DeliveryConfig.from(null));
    }

    @Test
    void roundTripThroughChannelTarget() {
        ChannelTarget t = new ChannelTarget("user-1", "thread-a", "bot-x");
        DeliveryConfig dc = DeliveryConfig.from(t);
        assertEquals(t, dc.toChannelTarget());
    }

    @Test
    void jsonRoundTrip_preservesAllFields() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeliveryConfig original = new DeliveryConfig("user-1", "thread-a", "bot-x");
        String json = om.writeValueAsString(original);
        DeliveryConfig restored = om.readValue(json, DeliveryConfig.class);
        assertEquals(original, restored);
    }

    @Test
    void jsonDeserialize_unknownFieldsAreIgnored() throws Exception {
        ObjectMapper om = new ObjectMapper();
        String json = "{\"targetId\":\"u\",\"threadId\":null,\"accountId\":null,\"newFieldFromFuture\":\"y\"}";
        DeliveryConfig dc = om.readValue(json, DeliveryConfig.class);
        assertEquals("u", dc.targetId());
    }

    // ── RFC-03 Lane C1: suppressAgentReply ─────────────────────────────────

    @Test
    void suppressAgentReply_defaultsToFalse_legacyCtor3arg() {
        // Pre-RFC-03 callsite — no suppress arg means historical behavior.
        DeliveryConfig dc = new DeliveryConfig("u", null, null);
        assertFalse(dc.isAgentReplySuppressed());
        assertNull(dc.suppressAgentReply());
    }

    @Test
    void suppressAgentReply_defaultsToFalse_legacyCtor4arg() {
        // 4-arg legacy ctor (post-userId, pre-suppress).
        DeliveryConfig dc = new DeliveryConfig("u", null, null, "sender");
        assertFalse(dc.isAgentReplySuppressed());
        assertNull(dc.suppressAgentReply());
    }

    @Test
    void suppressAgentReply_explicitFalseStillDelivers() {
        DeliveryConfig dc = new DeliveryConfig("u", null, null, null, Boolean.FALSE);
        assertFalse(dc.isAgentReplySuppressed(),
                "explicit FALSE must be treated identically to null — both deliver");
    }

    @Test
    void suppressAgentReply_trueShortCircuits() {
        DeliveryConfig dc = new DeliveryConfig("u", null, null, null, Boolean.TRUE);
        assertTrue(dc.isAgentReplySuppressed());
    }

    @Test
    void suppressAgentReply_jsonRoundTrip() throws Exception {
        ObjectMapper om = new ObjectMapper();
        DeliveryConfig original = new DeliveryConfig("u", "t", "a", "sender", Boolean.TRUE);
        String json = om.writeValueAsString(original);
        DeliveryConfig restored = om.readValue(json, DeliveryConfig.class);
        assertEquals(original, restored);
        assertTrue(restored.isAgentReplySuppressed());
    }

    @Test
    void suppressAgentReply_preV75JsonRow_treatedAsFalse() throws Exception {
        // Rows persisted before V75 don't have suppressAgentReply at all —
        // round-trip must surface as null and isAgentReplySuppressed=false.
        ObjectMapper om = new ObjectMapper();
        String legacyJson = "{\"targetId\":\"u\",\"threadId\":\"t\",\"accountId\":\"a\",\"userId\":\"sender\"}";
        DeliveryConfig dc = om.readValue(legacyJson, DeliveryConfig.class);
        assertNull(dc.suppressAgentReply());
        assertFalse(dc.isAgentReplySuppressed());
    }
}
