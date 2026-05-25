package vip.mate.channel.feishu.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.cards.CardOversizedException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pin the encode / decode contract for the tool-guard approval card
 * button {@code value} field. Round-trips matter because Feishu
 * deserialises the value to a Map and we need to identify which
 * pending approval and which decision the click came from.
 */
class ToolGuardButtonValueTest {

    private final ToolGuardButtonValue encoder = new ToolGuardButtonValue(new ObjectMapper());

    @Test
    @DisplayName("approve round-trip preserves action / pendingId / tool / severity")
    void approveRoundTrip() {
        Map<String, Object> value = encoder.encode(
                ToolGuardButtonValue.Action.APPROVE, "pend-1", "feishu_doc_create", "HIGH");

        ToolGuardButtonValue.Decoded decoded = encoder.decode(value);
        assertNotNull(decoded);
        assertEquals(ToolGuardButtonValue.Action.APPROVE, decoded.action());
        assertEquals("pend-1", decoded.pendingId());
        assertEquals("feishu_doc_create", decoded.toolName());
        assertEquals("HIGH", decoded.severity());
    }

    @Test
    @DisplayName("deny round-trip preserves the deny action")
    void denyRoundTrip() {
        Map<String, Object> value = encoder.encode(
                ToolGuardButtonValue.Action.DENY, "pend-2", "feishu_calendar_create_event", "MEDIUM");
        ToolGuardButtonValue.Decoded decoded = encoder.decode(value);
        assertEquals(ToolGuardButtonValue.Action.DENY, decoded.action());
        assertEquals("pend-2", decoded.pendingId());
    }

    @Test
    @DisplayName("decode rejects unknown / missing action with null")
    void decodeRejectsUnknownAction() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("action", "unknown.thing");
        bad.put("rid", "pend-1");
        assertNull(encoder.decode(bad));

        bad.remove("action");
        assertNull(encoder.decode(bad));
    }

    @Test
    @DisplayName("decode rejects missing pendingId with null")
    void decodeRejectsMissingPendingId() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("action", ToolGuardButtonValue.ACTION_APPROVE);
        assertNull(encoder.decode(bad));

        bad.put("rid", "");
        assertNull(encoder.decode(bad));
    }

    @Test
    @DisplayName("decode is tolerant of null / empty input")
    void decodeTolerantOfNullEmpty() {
        assertNull(encoder.decode(null));
        assertNull(encoder.decode(Map.of()));
    }

    @Test
    @DisplayName("encode rejects oversize payload with CardOversizedException")
    void encodeRejectsOversize() {
        // Build a tool name that will push the JSON well past MAX_VALUE_BYTES (2048)
        String huge = "x".repeat(3000);
        CardOversizedException ex = assertThrows(CardOversizedException.class,
                () -> encoder.encode(ToolGuardButtonValue.Action.APPROVE, "pend-1", huge, "HIGH"));
        assertEquals(true, ex.getMessage().contains("> limit"));
    }

    @Test
    @DisplayName("encoded JSON key order is stable (LinkedHashMap → predictable byte length)")
    void encodedFieldOrderStable() {
        Map<String, Object> a = encoder.encode(
                ToolGuardButtonValue.Action.APPROVE, "p-1", "tool-a", "HIGH");
        Map<String, Object> b = encoder.encode(
                ToolGuardButtonValue.Action.APPROVE, "p-1", "tool-a", "HIGH");
        assertEquals(a.keySet().iterator().next(), b.keySet().iterator().next());
    }
}
