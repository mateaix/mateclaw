package vip.mate.trigger.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.trigger.model.TriggerEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit coverage for {@link TriggerPatternMatcher}: the matcher is a
 * pure function of (trigger row, envelope) and pulls no Spring beans, so
 * tests stay POJO-only and run in milliseconds.
 *
 * <p>The shape of these cases enforces the design intent: cron is
 * scheduler-driven (never fires from ingest), webhook is opaque
 * pass-through, and unknown pattern types fail closed instead of
 * fan-firing every workspace trigger.
 */
class TriggerPatternMatcherTest {

    private final TriggerPatternMatcher matcher = new TriggerPatternMatcher(new ObjectMapper());

    @Test
    @DisplayName("Cron patterns never match an inbound envelope — they fire from the scheduler.")
    void cronAlwaysReturnsFalse() {
        TriggerEntity t = trigger("cron", "{\"cron\":\"0 * * * * *\"}");
        TriggerEventEnvelope env = envelope("cron", Map.of());
        assertFalse(matcher.matches(t, env));
    }

    @Test
    @DisplayName("Webhook patterns are pass-through; the secret check happens at the HTTP entry.")
    void webhookAlwaysReturnsTrue() {
        TriggerEntity t = trigger("webhook", "{}");
        TriggerEventEnvelope env = envelope("webhook", Map.of());
        assertTrue(matcher.matches(t, env));
    }

    @Test
    @DisplayName("channel_message narrows to channelType / senderEquals when present.")
    void channelMessageNarrowsByChannelType() {
        TriggerEntity t = trigger("channel_message", "{\"channelType\":\"feishu\"}");
        // channelType lives in envelope.data() — the controller stuffs it
        // there because the envelope record itself is generic.
        assertTrue(matcher.matches(t, envelope("channel_message", Map.of("channelType", "feishu"))));
        assertFalse(matcher.matches(t, envelope("channel_message", Map.of("channelType", "telegram"))));
        assertFalse(matcher.matches(t, envelope("channel_message", Map.of())));
    }

    @Test
    @DisplayName("channel_message narrows to senderEquals when present.")
    void channelMessageNarrowsBySender() {
        TriggerEntity t = trigger("channel_message", "{\"senderEquals\":\"alice\"}");
        assertTrue(matcher.matches(t, envelope("channel_message", "alice", Map.of())));
        assertFalse(matcher.matches(t, envelope("channel_message", "bob", Map.of())));
    }

    @Test
    @DisplayName("content_match needs a non-blank substring or it refuses to fire.")
    void contentMatchRefusesBlankSubstring() {
        TriggerEntity blank = trigger("content_match", "{}");
        assertFalse(matcher.matches(blank,
                envelope("content_match", Map.of("content", "anything"))));

        TriggerEntity needle = trigger("content_match", "{\"substring\":\"order\"}");
        assertTrue(matcher.matches(needle,
                envelope("content_match", Map.of("content", "Place an Order, please"))));
        assertFalse(matcher.matches(needle,
                envelope("content_match", Map.of("content", "no relevant text"))));
    }

    @Test
    @DisplayName("workflow_completion can narrow to source and state.")
    void workflowCompletionNarrows() {
        // The runner emits state="succeeded"; the pattern's stateFilter
        // accepts either the runner's vocabulary ("succeeded") or the
        // ergonomic alias "completed" — both should match a succeeded run.
        TriggerEntity t = trigger("workflow_completion",
                "{\"sourceWorkflowId\":42,\"stateFilter\":\"completed\"}");
        assertTrue(matcher.matches(t, envelope("workflow_completion",
                Map.of("sourceWorkflowId", 42L, "state", "succeeded"))));
        assertFalse(matcher.matches(t, envelope("workflow_completion",
                Map.of("sourceWorkflowId", 42L, "state", "failed"))));
        assertFalse(matcher.matches(t, envelope("workflow_completion",
                Map.of("sourceWorkflowId", 99L, "state", "succeeded"))));

        // stateFilter="failed" matches the runner's literal "failed" state.
        TriggerEntity onFail = trigger("workflow_completion",
                "{\"sourceWorkflowId\":42,\"stateFilter\":\"failed\"}");
        assertTrue(matcher.matches(onFail, envelope("workflow_completion",
                Map.of("sourceWorkflowId", 42L, "state", "failed"))));
        assertFalse(matcher.matches(onFail, envelope("workflow_completion",
                Map.of("sourceWorkflowId", 42L, "state", "succeeded"))));
    }

    @Test
    @DisplayName("Unknown pattern types fail closed — must not fan-fire across the workspace.")
    void unknownPatternFailsClosed() {
        TriggerEntity t = trigger("does-not-exist", "{}");
        assertFalse(matcher.matches(t, envelope("does-not-exist", Map.of())));
    }

    @Test
    @DisplayName("Malformed pattern_json is treated as empty constraints, never a throw.")
    void malformedPatternJsonDoesNotThrow() {
        // channel_message with empty constraints is intentionally permissive
        // (matches any channel) — the test verifies no exception escapes,
        // not the boolean.
        TriggerEntity permissive = trigger("channel_message", "{ this is not json");
        assertTrue(matcher.matches(permissive, envelope("channel_message",
                Map.of("channelType", "feishu"))));

        // content_match without a substring refuses to fire — proves the
        // empty-constraint envelope still goes through the type-specific
        // gate instead of being silently treated as a wildcard.
        TriggerEntity strict = trigger("content_match", "{ this is not json");
        assertFalse(matcher.matches(strict, envelope("content_match",
                Map.of("content", "hello"))));
    }

    private static TriggerEntity trigger(String type, String json) {
        TriggerEntity t = new TriggerEntity();
        t.setId(1L);
        t.setPatternType(type);
        t.setPatternJson(json);
        return t;
    }

    private static TriggerEventEnvelope envelope(String type, Map<String, Object> data) {
        return envelope(type, "u1", data);
    }

    private static TriggerEventEnvelope envelope(String type, String senderId, Map<String, Object> data) {
        return new TriggerEventEnvelope(99L, type, "evt-" + System.nanoTime(), senderId, data);
    }
}
