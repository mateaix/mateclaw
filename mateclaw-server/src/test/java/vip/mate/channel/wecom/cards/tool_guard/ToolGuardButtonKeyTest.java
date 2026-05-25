package vip.mate.channel.wecom.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.cards.CardOversizedException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the WeCom 1024-byte button.key encoding contract.
 *
 * <p>The encoding is the only place in PR-1 where a card payload can
 * exceed a hard server limit and force the adapter to fall back to
 * text. These tests pin both the happy-path encoding shape and the
 * overflow behaviour so future changes to button.key fields can't
 * silently break either.
 */
class ToolGuardButtonKeyTest {

    private ToolGuardButtonKey buttonKey;

    @BeforeEach
    void setUp() {
        buttonKey = new ToolGuardButtonKey(new ObjectMapper());
    }

    @Test
    @DisplayName("encode produces decodable JSON with stable field order")
    void encodeDecodeRoundTrip() {
        String encoded = buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE,
                "abc123def456",
                "shell_exec",
                "HIGH"
        );
        // Stable order ensures byte-length predictability + makes log
        // greps deterministic.
        assertTrue(encoded.startsWith("{\"a\":\"approve\""),
                "first field must be 'a' (action); got: " + encoded);
        assertTrue(encoded.contains("\"rid\":\"abc123def456\""));
        assertTrue(encoded.contains("\"tool\":\"shell_exec\""));
        assertTrue(encoded.contains("\"sev\":\"HIGH\""));

        ToolGuardButtonKey.Decoded decoded = buttonKey.decode(encoded);
        assertNotNull(decoded);
        assertEquals(ToolGuardButtonKey.Action.APPROVE, decoded.action());
        assertEquals("abc123def456", decoded.pendingId());
        assertEquals("shell_exec", decoded.toolName());
        assertEquals("HIGH", decoded.severity());
    }

    @Test
    @DisplayName("encode throws CardOversizedException at exactly the 1024-byte threshold")
    void overflowAt1024Bytes() {
        // toolName 1100 chars of pure ASCII (1100 bytes) — single character per byte
        // forces the JSON over 1024 even with all the structural overhead.
        String hugeTool = "x".repeat(1100);
        CardOversizedException ex = assertThrows(CardOversizedException.class,
                () -> buttonKey.encode(
                        ToolGuardButtonKey.Action.DENY,
                        "rid",
                        hugeTool,
                        "MEDIUM"));
        assertTrue(ex.getMessage().contains("button.key payload"),
                "exception message should reference button.key payload, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1024"),
                "exception message should mention the 1024 limit, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("encode handles Chinese tool names within the 1024-byte budget")
    void encodeChineseToolName() {
        String chinese = "执行命令".repeat(40);  // 4 chars * 40 = 160 chars, ~480 UTF-8 bytes
        String encoded = buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE,
                "uuid-1234",
                chinese,
                "MEDIUM"
        );
        // sanity: each Chinese char = 3 UTF-8 bytes; 160 chars ≈ 480 bytes;
        // overhead ≈ 50 bytes; total well under 1024
        int bytes = encoded.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < 1024, "expected < 1024 bytes for moderate Chinese, got " + bytes);
        ToolGuardButtonKey.Decoded decoded = buttonKey.decode(encoded);
        assertNotNull(decoded);
        assertEquals(chinese, decoded.toolName());
    }

    @Test
    @DisplayName("decode returns null for malformed JSON, unknown action, or missing rid")
    void decodeMalformed() {
        // Garbage JSON
        assertNull(buttonKey.decode("not json"));
        assertNull(buttonKey.decode("{not closed"));
        // Unknown action
        assertNull(buttonKey.decode("{\"a\":\"reboot\",\"rid\":\"x\"}"));
        // Missing rid
        assertNull(buttonKey.decode("{\"a\":\"approve\"}"));
        // Blank rid
        assertNull(buttonKey.decode("{\"a\":\"approve\",\"rid\":\"\"}"));
        // Null / blank input
        assertNull(buttonKey.decode(null));
        assertNull(buttonKey.decode(""));
        assertNull(buttonKey.decode("   "));
    }

    @Test
    @DisplayName("decode tolerates extra/unknown fields (forward-compat)")
    void decodeForwardCompat() {
        String json = "{\"a\":\"deny\",\"rid\":\"r1\",\"tool\":\"t\",\"sev\":\"LOW\",\"future\":42}";
        ToolGuardButtonKey.Decoded decoded = buttonKey.decode(json);
        assertNotNull(decoded);
        assertEquals(ToolGuardButtonKey.Action.DENY, decoded.action());
    }

    @Test
    @DisplayName("encoded JSON respects the 1024-byte boundary on either side")
    void boundaryExact() {
        // 950 ASCII chars + JSON overhead (~50 bytes for the structural braces,
        // commas, quotes, and the 'a'/'rid'/'tool'/'sev' field labels) lands
        // around 1010 bytes — comfortably under the 1024 limit.
        String near = "a".repeat(950);
        String encoded = buttonKey.encode(
                ToolGuardButtonKey.Action.APPROVE,
                "x",
                near,
                "M"
        );
        assertNotNull(encoded);
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= ToolGuardButtonKey.MAX_KEY_BYTES,
                "950-char tool name must encode within 1024 bytes; got "
                        + encoded.getBytes(StandardCharsets.UTF_8).length);

        // Push past the limit — must throw
        String over = "a".repeat(1100);
        assertThrows(CardOversizedException.class,
                () -> buttonKey.encode(ToolGuardButtonKey.Action.APPROVE, "x", over, "M"));
    }
}
