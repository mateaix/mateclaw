package vip.mate.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantThinkingRelay} — RelayEntry carries both per-assistant thinking
 * and the caller's original {@code user} field, so the consumer can restore it
 * when rebuilding the outbound request.
 */
class AssistantThinkingRelayTest {

    @BeforeEach
    void clear() {
        AssistantThinkingRelay.clearAll();
    }

    @AfterEach
    void tearDown() {
        AssistantThinkingRelay.clearAll();
    }

    @Test
    @DisplayName("stash returns token with expected prefix")
    void stash_returnsTokenWithPrefix() {
        String token = AssistantThinkingRelay.stash(List.of("thinking-a"), null);
        assertTrue(AssistantThinkingRelay.isToken(token));
        assertTrue(token.startsWith(AssistantThinkingRelay.TOKEN_PREFIX));
    }

    @Test
    @DisplayName("stash + take roundtrips thinkings in order and originalUser")
    void stashTake_roundtrip() {
        List<String> thinkings = List.of("one", "", "three");
        String token = AssistantThinkingRelay.stash(thinkings, "caller-user-42");
        AssistantThinkingRelay.RelayEntry entry = AssistantThinkingRelay.take(token);

        assertNotNull(entry);
        assertEquals(List.of("one", "", "three"), entry.thinkings());
        assertEquals("caller-user-42", entry.originalUser());
    }

    @Test
    @DisplayName("stash + take with null originalUser preserves null")
    void stashTake_nullOriginalUser() {
        String token = AssistantThinkingRelay.stash(List.of("x"), null);
        AssistantThinkingRelay.RelayEntry entry = AssistantThinkingRelay.take(token);
        assertNotNull(entry);
        assertNull(entry.originalUser());
    }

    @Test
    @DisplayName("take removes entry — subsequent take returns null")
    void take_removesEntry() {
        String token = AssistantThinkingRelay.stash(List.of("x"), "u");
        assertNotNull(AssistantThinkingRelay.take(token));
        assertNull(AssistantThinkingRelay.take(token));
    }

    @Test
    @DisplayName("take on non-token user returns null")
    void take_onNonToken_returnsNull() {
        assertNull(AssistantThinkingRelay.take(null));
        assertNull(AssistantThinkingRelay.take(""));
        assertNull(AssistantThinkingRelay.take("some-real-user-id"));
    }

    @Test
    @DisplayName("isToken: prefix-based detection")
    void isToken_prefixDetection() {
        assertFalse(AssistantThinkingRelay.isToken(null));
        assertFalse(AssistantThinkingRelay.isToken(""));
        assertFalse(AssistantThinkingRelay.isToken("regular-user"));
        assertTrue(AssistantThinkingRelay.isToken(AssistantThinkingRelay.TOKEN_PREFIX + "anything"));
    }

    @Test
    @DisplayName("discard after take is a no-op (idempotent)")
    void discard_idempotent() {
        String token = AssistantThinkingRelay.stash(List.of("x"), "u");
        AssistantThinkingRelay.take(token);
        // Should not throw and not affect other entries
        AssistantThinkingRelay.discard(token);
        assertEquals(0, AssistantThinkingRelay.size());
    }

    @Test
    @DisplayName("discard without take removes the entry (producer failure path)")
    void discard_withoutTake_removes() {
        String token = AssistantThinkingRelay.stash(List.of("x"), "u");
        assertEquals(1, AssistantThinkingRelay.size());
        AssistantThinkingRelay.discard(token);
        assertEquals(0, AssistantThinkingRelay.size());
        // Subsequent take still returns null
        assertNull(AssistantThinkingRelay.take(token));
    }

    @Test
    @DisplayName("concurrent stashes produce distinct tokens")
    void stash_distinctTokens() {
        String a = AssistantThinkingRelay.stash(List.of("a"), "ua");
        String b = AssistantThinkingRelay.stash(List.of("b"), "ub");
        assertNotEquals(a, b);

        AssistantThinkingRelay.RelayEntry ea = AssistantThinkingRelay.take(a);
        AssistantThinkingRelay.RelayEntry eb = AssistantThinkingRelay.take(b);
        assertEquals(List.of("a"), ea.thinkings());
        assertEquals("ua", ea.originalUser());
        assertEquals(List.of("b"), eb.thinkings());
        assertEquals("ub", eb.originalUser());
    }

    @Test
    @DisplayName("RelayEntry.thinkings is immutable (defensive copy)")
    void relayEntry_thinkingsImmutable() {
        java.util.ArrayList<String> mutable = new java.util.ArrayList<>(List.of("a", "b"));
        String token = AssistantThinkingRelay.stash(mutable, "u");
        mutable.set(0, "mutated");  // should not affect the stashed copy

        AssistantThinkingRelay.RelayEntry entry = AssistantThinkingRelay.take(token);
        assertEquals(List.of("a", "b"), entry.thinkings());

        // thinkings returned is also unmodifiable
        assertThrows(UnsupportedOperationException.class,
                () -> entry.thinkings().set(0, "x"));
    }
}
