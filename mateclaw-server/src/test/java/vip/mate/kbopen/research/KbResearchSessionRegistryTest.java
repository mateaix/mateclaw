package vip.mate.kbopen.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.kbopen.research.KbResearchSessionRegistry.Session;
import vip.mate.kbopen.research.KbResearchSessionRegistry.Status;
import vip.mate.wiki.service.WikiResearchService.ResearchResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link KbResearchSessionRegistry} — session lifecycle and status
 * transitions.
 */
class KbResearchSessionRegistryTest {

    @Test
    @DisplayName("register creates a RUNNING session")
    void registerCreatesRunning() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry();
        registry.register("s1", 100L, 10L, "test topic");

        Optional<Session> session = registry.get("s1");
        assertThat(session).isPresent();
        assertThat(session.get().status()).isEqualTo(Status.RUNNING);
        assertThat(session.get().keyId()).isEqualTo(100L);
        assertThat(session.get().kbId()).isEqualTo(10L);
        assertThat(session.get().topic()).isEqualTo("test topic");
    }

    @Test
    @DisplayName("complete transitions RUNNING → COMPLETED with result")
    void completeTransitionsToCompleted() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry();
        registry.register("s1", 100L, 10L, "topic");

        ResearchResult result = new ResearchResult("topic", List.of(), "final report");
        registry.complete("s1", result);

        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.COMPLETED);
        assertThat(session.result()).isEqualTo(result);
        assertThat(session.result().report()).isEqualTo("final report");
    }

    @Test
    @DisplayName("fail transitions RUNNING → FAILED with error")
    void failTransitionsToFailed() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry();
        registry.register("s1", 100L, 10L, "topic");

        registry.fail("s1", "LLM timeout");

        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.FAILED);
        assertThat(session.error()).isEqualTo("LLM timeout");
    }

    @Test
    @DisplayName("cancel transitions RUNNING → CANCELLED")
    void cancelTransitionsToCancelled() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry();
        registry.register("s1", 100L, 10L, "topic");

        boolean cancelled = registry.cancel("s1");

        assertThat(cancelled).isTrue();
        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.CANCELLED);
    }

    @Test
    @DisplayName("cancel on non-running session returns false (no-op)")
    void cancelOnCompletedIsNoop() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry();
        registry.register("s1", 100L, 10L, "topic");
        registry.complete("s1", new ResearchResult("topic", List.of(), "report"));

        // cancel on COMPLETED should not change status
        registry.cancel("s1");
        Session session = registry.get("s1").get();
        assertThat(session.status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    @DisplayName("get on unknown session returns empty")
    void getUnknownReturnsEmpty() {
        KbResearchSessionRegistry registry = new KbResearchSessionRegistry();
        assertThat(registry.get("nonexistent")).isEmpty();
    }
}
