package vip.mate.kbopen.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.kbopen.research.KbResearchSessionRegistry.Session;
import vip.mate.kbopen.research.KbResearchSessionRegistry.Status;
import vip.mate.kbopen.research.KbResearchSessionRegistry.TooManyConcurrentException;
import vip.mate.wiki.service.WikiResearchService.ResearchResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link KbResearchSessionRegistry} — session lifecycle, status
 * transitions, sticky CANCELLED terminal, per-key concurrency cap, and TTL
 * eviction.
 */
class KbResearchSessionRegistryTest {

    private static final ResearchResult RESULT = new ResearchResult("topic", List.of(), "final report");

    private KbResearchSessionRegistry newRegistry() {
        return new KbResearchSessionRegistry(3, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("register creates a RUNNING session")
    void registerCreatesRunning() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "test topic");

        Optional<Session> session = registry.get("s1");
        assertThat(session).isPresent();
        assertThat(session.get().status()).isEqualTo(Status.RUNNING);
        assertThat(session.get().keyId()).isEqualTo(100L);
        assertThat(session.get().kbId()).isEqualTo(10L);
        assertThat(session.get().topic()).isEqualTo("test topic");
        assertThat(session.get().updatedAt()).isNotNull();
    }

    @Test
    @DisplayName("complete transitions RUNNING → COMPLETED with result")
    void completeTransitionsToCompleted() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "topic");

        registry.complete("s1", RESULT);

        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.COMPLETED);
        assertThat(session.result()).isEqualTo(RESULT);
        assertThat(session.result().report()).isEqualTo("final report");
    }

    @Test
    @DisplayName("fail transitions RUNNING → FAILED with error")
    void failTransitionsToFailed() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "topic");

        registry.fail("s1", "LLM timeout");

        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.FAILED);
        assertThat(session.error()).isEqualTo("LLM timeout");
    }

    @Test
    @DisplayName("cancel transitions RUNNING → CANCELLED")
    void cancelTransitionsToCancelled() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "topic");

        boolean cancelled = registry.cancel("s1");

        assertThat(cancelled).isTrue();
        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.CANCELLED);
    }

    @Test
    @DisplayName("cancel on non-running session returns false (no-op)")
    void cancelOnCompletedIsNoop() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "topic");
        registry.complete("s1", RESULT);

        boolean cancelled = registry.cancel("s1");

        assertThat(cancelled).isFalse();
        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    @DisplayName("get on unknown session returns empty")
    void getUnknownReturnsEmpty() {
        KbResearchSessionRegistry registry = newRegistry();
        assertThat(registry.get("nonexistent")).isEmpty();
    }

    // ── Review #446: sticky CANCELLED terminal ────────────────────────────

    @Test
    @DisplayName("complete after cancel is a no-op — CANCELLED is sticky")
    void completeAfterCancelIsNoop() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "topic");
        registry.cancel("s1");

        // Late complete() arriving from the async pipeline must NOT overwrite
        // the CANCELLED terminal the user explicitly requested.
        registry.complete("s1", RESULT);

        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.CANCELLED);
        assertThat(session.result()).isNull();
    }

    @Test
    @DisplayName("fail after cancel is a no-op — CANCELLED is sticky")
    void failAfterCancelIsNoop() {
        KbResearchSessionRegistry registry = newRegistry();
        registry.register("s1", 100L, 10L, "topic");
        registry.cancel("s1");

        registry.fail("s1", "race condition");

        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.CANCELLED);
        assertThat(session.error()).isNull();
    }

    // ── Review #446: per-key concurrency cap ──────────────────────────────

    @Test
    @DisplayName("startIfAllowed throws once the per-key cap is reached")
    void startIfAllowedEnforcesCap() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry(2, Duration.ofMinutes(30));
        registry.startIfAllowed("s1", 100L, 10L, "t1");
        registry.startIfAllowed("s2", 100L, 10L, "t2");

        // Third running session for the same key should be rejected → 429 upstream.
        assertThatThrownBy(() -> registry.startIfAllowed("s3", 100L, 10L, "t3"))
                .isInstanceOf(TooManyConcurrentException.class)
                .hasMessageContaining("limit is 2");

        // A different key is unaffected (cap is per-key, not global).
        registry.startIfAllowed("s4", 200L, 10L, "t4");
        assertThat(registry.get("s4")).isPresent();
    }

    @Test
    @DisplayName("completed sessions do not count toward the running cap")
    void completedDoesNotCountTowardCap() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry(1, Duration.ofMinutes(30));
        registry.startIfAllowed("s1", 100L, 10L, "t1");
        registry.complete("s1", RESULT);

        // The terminal session no longer occupies a slot.
        registry.startIfAllowed("s2", 100L, 10L, "t2");
        assertThat(registry.get("s2")).isPresent();
    }

    // ── Review #446: TTL eviction ─────────────────────────────────────────

    @Test
    @DisplayName("evictExpired removes terminal sessions past TTL but keeps RUNNING + fresh terminals")
    void evictExpiredRemovesStaleTerminals() {
        // Tiny TTL so a fresh terminal (updatedAt≈now) is clearly within window.
        Duration ttl = Duration.ofSeconds(60);
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry(3, ttl);
        registry.register("s1", 100L, 10L, "running");        // RUNNING — never evicted
        registry.register("s2", 100L, 10L, "stale-completed");
        registry.complete("s2", RESULT);                        // terminal, will be aged
        registry.register("s3", 100L, 10L, "fresh-cancelled");
        registry.cancel("s3");                                  // terminal, fresh

        // Horizon well past TTL: both terminals s2 and s3 are now stale.
        Instant far = Instant.now().plus(Duration.ofSeconds(120));
        int removed = registry.evictExpired(far);
        assertThat(removed).isEqualTo(2);
        assertThat(registry.get("s1")).isPresent();  // running always kept
        assertThat(registry.get("s2")).isEmpty();
        assertThat(registry.get("s3")).isEmpty();
    }

    @Test
    @DisplayName("evictExpired keeps a fresh terminal within the TTL window")
    void evictExpiredKeepsFreshTerminal() {
        Duration ttl = Duration.ofHours(1);
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry(3, ttl);
        registry.register("s1", 100L, 10L, "just-completed");
        registry.complete("s1", RESULT);                        // updatedAt ≈ now

        // Evict only 5 seconds later — well within the 1h TTL.
        int removed = registry.evictExpired(Instant.now().plus(Duration.ofSeconds(5)));
        assertThat(removed).isZero();
        assertThat(registry.get("s1")).isPresent();
    }
}
