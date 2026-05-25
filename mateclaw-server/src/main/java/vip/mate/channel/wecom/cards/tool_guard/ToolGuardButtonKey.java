package vip.mate.channel.wecom.cards.tool_guard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.channel.cards.CardOversizedException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON encode/decode helper for the {@code key} field on each
 * tool-guard approval card button.
 *
 * <p>WeCom enforces a hard 1024-byte ceiling on each
 * {@code button.key} — the field is what the server echoes back as
 * {@code event_key} when the user clicks. We pack the action plus the
 * minimal context we need to recover the pending approval (the
 * {@code pendingId} alone is enough — mateclaw's
 * {@code ApprovalService.findById} resolves the rest, including the
 * original requester). Sender/chat context is intentionally not packed
 * — the inbound handler runs in-process and can do a synchronous DB
 * lookup, leaving headroom in the 1024-byte budget for long tool names
 * / Chinese characters.
 *
 * <p>Encoding is stable (LinkedHashMap → consistent key order so byte-
 * length is predictable). Decoding tolerates extra fields — useful if
 * a future change adds optional context.
 */
public final class ToolGuardButtonKey {

    /** Hard byte limit for the {@code button.key} field; verified against WeCom protocol. */
    public static final int MAX_KEY_BYTES = 1024;

    public enum Action {
        APPROVE("approve"),
        DENY("deny");

        public final String wireValue;
        Action(String v) { this.wireValue = v; }

        public static Action fromWire(String v) {
            if ("approve".equalsIgnoreCase(v)) return APPROVE;
            if ("deny".equalsIgnoreCase(v)) return DENY;
            return null;
        }
    }

    /** Decoded button-key payload. {@code null} if parse fails or action invalid. */
    public record Decoded(Action action, String pendingId, String toolName, String severity) {}

    private final ObjectMapper objectMapper;

    public ToolGuardButtonKey(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Encode an approval-button {@code key}.
     *
     * @throws CardOversizedException if the resulting JSON exceeds 1024
     *         bytes (caller must fall back to text approval path)
     */
    public String encode(Action action, String pendingId, String toolName, String severity) {
        // LinkedHashMap so the key order on the wire is stable across calls;
        // makes byte-length predictable and snapshot-testable.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("a", action.wireValue);   // action
        payload.put("rid", pendingId);         // request/pending id
        payload.put("tool", toolName);         // for log readability when WeCom replays event_key
        payload.put("sev", severity == null ? "" : severity);
        try {
            String json = objectMapper.writeValueAsString(payload);
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_KEY_BYTES) {
                throw new CardOversizedException(
                        "tool_guard button.key payload " + bytes + " bytes > limit " + MAX_KEY_BYTES);
            }
            return json;
        } catch (CardOversizedException e) {
            throw e;
        } catch (Exception e) {
            throw new CardOversizedException("failed to serialise button.key: " + e.getMessage());
        }
    }

    /**
     * Decode the {@code event_key} echoed back by WeCom on button click.
     * Returns {@code null} if the payload is malformed or the action
     * unrecognised. Callers should treat null as "ignore this event".
     */
    public Decoded decode(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) return null;
        try {
            Map<String, Object> raw = objectMapper.readValue(eventKey, new TypeReference<>() {});
            Action action = Action.fromWire(asString(raw.get("a")));
            if (action == null) return null;
            String pendingId = asString(raw.get("rid"));
            if (pendingId == null || pendingId.isBlank()) return null;
            return new Decoded(
                    action,
                    pendingId,
                    asString(raw.getOrDefault("tool", "")),
                    asString(raw.getOrDefault("sev", "")));
        } catch (Exception e) {
            // Malformed payload — treat as "ignore". The caller's
            // log.debug at handler entry covers visibility.
            return null;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
