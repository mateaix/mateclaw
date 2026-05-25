package vip.mate.channel.feishu.cards.tool_guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.channel.cards.CardOversizedException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encode / decode the button {@code value} field on a tool-guard
 * approval card.
 *
 * <p>Feishu Schema-2.0 buttons carry a free-form JSON object as
 * {@code value}. The server echoes it back inside the inbound
 * {@code P2CardActionTrigger}'s {@code action.value}. We pack just
 * enough to recover the pending approval (the {@code pendingId}
 * alone is enough — mateclaw's {@code ApprovalService.getPending}
 * resolves the rest, including the original requester). Sender / chat
 * context is intentionally not packed — the inbound handler runs in-
 * process and can look it up synchronously.
 *
 * <p>Feishu does not publish an explicit byte ceiling on the value
 * field but interactive-content as a whole is capped at ~30 KB.
 * {@link #MAX_VALUE_BYTES} keeps our share well below that so the rest
 * of the card body fits even when the tool name is verbose.
 */
public final class ToolGuardButtonValue {

    /** Discriminator action prefix shared with {@link ToolGuardCardKindFactory}. */
    public static final String ACTION_PREFIX = "tg_approval.";

    public static final String ACTION_APPROVE = ACTION_PREFIX + "approve";
    public static final String ACTION_DENY = ACTION_PREFIX + "deny";

    /** Soft cap on the serialised value payload (well below Feishu's overall ~30 KB cap). */
    public static final int MAX_VALUE_BYTES = 2048;

    public enum Action {
        APPROVE(ACTION_APPROVE),
        DENY(ACTION_DENY);

        public final String wireValue;
        Action(String v) { this.wireValue = v; }

        public static Action fromWire(String v) {
            if (v == null) return null;
            if (ACTION_APPROVE.equals(v)) return APPROVE;
            if (ACTION_DENY.equals(v)) return DENY;
            return null;
        }
    }

    public record Decoded(Action action, String pendingId, String toolName, String severity) {}

    private final ObjectMapper objectMapper;

    public ToolGuardButtonValue(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Build the Map that goes into the Schema-2.0 button's {@code value}
     * field. LinkedHashMap so the JSON serialisation order is stable —
     * makes byte-length predictable and snapshot-testable.
     *
     * @throws CardOversizedException when the resulting JSON would
     *         exceed {@link #MAX_VALUE_BYTES}; caller falls back to text
     */
    public Map<String, Object> encode(Action action, String pendingId, String toolName, String severity) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action.wireValue);
        payload.put("rid", pendingId);
        payload.put("tool", toolName == null ? "" : toolName);
        payload.put("sev", severity == null ? "" : severity);
        // Size-check via a one-off JSON serialisation so we surface the
        // overflow at render time rather than letting Feishu reject the
        // whole interactive message at send time.
        try {
            String json = objectMapper.writeValueAsString(payload);
            int bytes = json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_VALUE_BYTES) {
                throw new CardOversizedException(
                        "tool_guard button.value payload " + bytes + " bytes > limit " + MAX_VALUE_BYTES);
            }
        } catch (CardOversizedException e) {
            throw e;
        } catch (Exception e) {
            throw new CardOversizedException("failed to serialise button.value: " + e.getMessage());
        }
        return payload;
    }

    /**
     * Decode the {@code action.value} echoed back by Feishu on click.
     * Returns null if the payload is malformed or the action
     * unrecognised. Callers should treat null as "ignore this event".
     */
    public Decoded decode(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return null;
        Action action = Action.fromWire(asString(value.get("action")));
        if (action == null) return null;
        String pendingId = asString(value.get("rid"));
        if (pendingId == null || pendingId.isBlank()) return null;
        return new Decoded(
                action,
                pendingId,
                asString(value.getOrDefault("tool", "")),
                asString(value.getOrDefault("sev", "")));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
