package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pin the FeishuStreamingCardManager state machine + throttle.
 *
 * <p>SDK calls are stubbed via {@code protected} seams so this test
 * stays Spring-free and network-free — we verify ordering and
 * lifecycle, not Feishu API shape (the IT covers wiring).
 *
 * <p>Behaviour pinned:
 * <ul>
 *   <li>throttle window suppresses sub-window flushes; forceFlush
 *       bypasses the UX throttle but retains the platform hard limit</li>
 *   <li>session is removed from {@code activeSessions} on terminal
 *       transition; subsequent appends are no-ops</li>
 *   <li>finish vs fail is a CAS-guarded one-shot — second terminal
 *       call is silent and does not double-close streaming</li>
 *   <li>sequence numbers are monotonic across content + close</li>
 *   <li>initial card JSON carries schema 2.0 + streaming_mode + the
 *       agreed element id</li>
 * </ul>
 */
class FeishuStreamingCardManagerTest {

    /** Recording SDK seam — captures each call so the test can replay them. */
    private static final class RecordingManager extends FeishuStreamingCardManager {
        record ContentCall(String cardId, String elementId, String content, int sequence) {}
        record CloseCall(String cardId, int sequence, String summary) {}

        final List<ContentCall> contentCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<CloseCall> closeCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicLong fakeNowMs = new AtomicLong(0);
        final AtomicReference<String> nextCardId = new AtomicReference<>("card_abc");
        final AtomicReference<String> nextMessageId = new AtomicReference<>("msg_abc");
        final AtomicInteger contentFailuresRemaining = new AtomicInteger();
        final AtomicInteger closeFailuresRemaining = new AtomicInteger();

        RecordingManager(FeishuClientFactory factory, ObjectMapper objectMapper) {
            super(factory, objectMapper);
        }

        @Override protected long currentTimeMs() { return fakeNowMs.get(); }

        @Override protected String sdkCreateCard(Client client, String initialText) { return nextCardId.get(); }

        @Override protected String sdkSendInteractiveMessage(Client client, String receiveIdType,
                                                              String receiveId, String cardId) {
            return nextMessageId.get();
        }

        @Override protected void sdkPushElementContent(Client client, String cardId, String elementId,
                                                        String content, int sequence) {
            if (contentFailuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated content failure");
            }
            contentCalls.add(new ContentCall(cardId, elementId, content, sequence));
        }

        @Override protected void sdkCloseStreamingMode(Client client, String cardId, int sequence,
                                                        String summary) {
            if (closeFailuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated close failure");
            }
            closeCalls.add(new CloseCall(cardId, sequence, summary));
        }

        @Override protected void sleepMillis(long millis) {
            fakeNowMs.addAndGet(millis);
        }
    }

    private RecordingManager manager;

    @BeforeEach
    void setUp() {
        FeishuClientFactory factory = mock(FeishuClientFactory.class);
        when(factory.client(anyLong())).thenReturn(mock(Client.class));
        // Mockito.any() also returns mock Client for boxed Long lookups
        when(factory.client(any())).thenReturn(mock(Client.class));
        manager = new RecordingManager(factory, new ObjectMapper());
    }

    @Test
    @DisplayName("createAndDeliver registers a session keyed by UUID")
    void createAndDeliverRegistersSession() {
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);
        assertNotNull(key);
        assertEquals(1, manager.activeSessionCount());
        assertNotNull(manager.sessionFor(key));
    }

    @Test
    @DisplayName("createAndDeliver returns null when card creation fails")
    void createReturnsNullOnFailure() {
        manager.nextCardId.set(null);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);
        assertNull(key);
        assertEquals(0, manager.activeSessionCount());
    }

    @Test
    @DisplayName("the very first append flushes immediately (no prior flush gates)")
    void firstAppendFlushesImmediately() {
        manager.fakeNowMs.set(1000L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);

        manager.fakeNowMs.set(1010L);  // only 10ms later — still flushes because no prior flush
        manager.appendContent(key, "Hel", false);

        assertEquals(1, manager.contentCalls.size(),
                "First-ever append should flush immediately so the user sees an instant first token");
        assertEquals("Hel", manager.contentCalls.get(0).content());
    }

    @Test
    @DisplayName("subsequent appends inside the throttle window are suppressed and accumulated")
    void throttleSuppressesPostFirstFlushAppends() {
        manager.fakeNowMs.set(1000L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);

        // First — flushes immediately (seq 1) and sets lastFlushMs=1010
        manager.fakeNowMs.set(1010L);
        manager.appendContent(key, "first ", false);
        assertEquals(1, manager.contentCalls.size());

        // Inside 500ms window → suppressed, just accumulated
        manager.fakeNowMs.set(1100L);
        manager.appendContent(key, "mid ", false);
        manager.fakeNowMs.set(1400L);
        manager.appendContent(key, "more ", false);
        assertEquals(1, manager.contentCalls.size(),
                "Mid-window appends should NOT trigger SDK calls");

        // Past 500ms window → flushes the full accumulator (seq 2)
        manager.fakeNowMs.set(1600L);
        manager.appendContent(key, "end", false);
        assertEquals(2, manager.contentCalls.size());
        assertEquals("first mid more end", manager.contentCalls.get(1).content());
        assertEquals(2, manager.contentCalls.get(1).sequence());
    }

    @Test
    @DisplayName("forceFlush bypasses the throttle even inside the window")
    void forceFlushBypassesThrottle() {
        manager.fakeNowMs.set(0L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);

        // First append flushes regardless (lastFlushMs=0)
        manager.appendContent(key, "a", false);
        assertEquals(1, manager.contentCalls.size());

        // 50ms later (inside 500ms window) — without force this would be suppressed
        manager.fakeNowMs.set(50L);
        manager.appendContent(key, "b", true);
        assertEquals(2, manager.contentCalls.size(), "forceFlush must bypass throttle");
        assertEquals("ab", manager.contentCalls.get(1).content());
    }

    @Test
    @DisplayName("full progress snapshots replace rather than append the previous card text")
    void updateContentReplacesSnapshot() {
        manager.fakeNowMs.set(0L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);

        manager.updateContent(key, "💭 思考中", true);
        manager.fakeNowMs.set(50L);
        manager.updateContent(key, "🔧 正在执行工具", true);

        assertEquals(2, manager.contentCalls.size());
        assertEquals("💭 思考中", manager.contentCalls.get(0).content());
        assertEquals("🔧 正在执行工具", manager.contentCalls.get(1).content(),
                "status transitions must not duplicate the preceding snapshot");
    }

    @Test
    @DisplayName("finishCard emits final content + close, in monotonic sequence order, then removes session")
    void finishCardClosesAndUnregisters() {
        manager.fakeNowMs.set(0L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);

        manager.appendContent(key, "Hello, ", true);   // seq 1
        manager.fakeNowMs.set(600L);
        manager.appendContent(key, "world", false);    // seq 2
        FeishuStreamingCardManager.FinishResult result =
                manager.finishCard(key, "Hello, world!"); // seq 3 (content) + seq 4 (close)

        assertEquals(3, manager.contentCalls.size());
        assertEquals("Hello, world!", manager.contentCalls.get(2).content());
        // Sequence is monotonic across all content + close calls
        assertEquals(1, manager.contentCalls.get(0).sequence());
        assertEquals(2, manager.contentCalls.get(1).sequence());
        assertEquals(3, manager.contentCalls.get(2).sequence());
        assertEquals(1, manager.closeCalls.size());
        assertEquals(4, manager.closeCalls.get(0).sequence());
        assertEquals("Hello, world!", manager.closeCalls.get(0).summary());
        assertTrue(result.success());
        assertEquals(0, manager.activeSessionCount());
    }

    @Test
    @DisplayName("terminal content failure is retried and reported to the adapter")
    void finishReportsContentFailureAfterRetry() {
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);
        manager.contentFailuresRemaining.set(2);

        FeishuStreamingCardManager.FinishResult result = manager.finishCard(key, "answer");

        assertFalse(result.finalContentUpdated());
        assertTrue(result.streamingClosed(), "the card should still leave streaming mode");
        assertEquals(0, manager.activeSessionCount());
    }

    @Test
    @DisplayName("close failure is retried and remains observable after both attempts fail")
    void finishReportsCloseFailureAfterRetry() {
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);
        manager.closeFailuresRemaining.set(2);

        FeishuStreamingCardManager.FinishResult result = manager.finishCard(key, "answer");

        assertTrue(result.finalContentUpdated());
        assertFalse(result.streamingClosed());
        assertEquals(0, manager.activeSessionCount());
    }

    @Test
    @DisplayName("summary strips markdown, collapses whitespace, and stays within preview limit")
    void summaryIsSuitableForChatPreview() {
        assertEquals("标题 内容", FeishuStreamingCardManager.summaryFor("## 标题\n\n**内容**"));
        assertEquals("✅ 已完成", FeishuStreamingCardManager.summaryFor("  "));
        assertEquals(80, FeishuStreamingCardManager.summaryFor("x".repeat(120)).length());
    }

    @Test
    @DisplayName("appendContent after finish is a no-op (no SDK call, no resurrection)")
    void appendAfterFinishIsNoop() {
        manager.fakeNowMs.set(0L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);
        manager.finishCard(key, "done");

        int before = manager.contentCalls.size();
        manager.fakeNowMs.set(10_000L);
        manager.appendContent(key, "ghost delta", true);
        assertEquals(before, manager.contentCalls.size(),
                "Append after terminal status must not produce another SDK call");
    }

    @Test
    @DisplayName("failCard appends error suffix, closes, and ignores second terminal call")
    void failCardAppendsTailAndIsOneShot() {
        manager.fakeNowMs.set(0L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);
        manager.appendContent(key, "Partial reply", true);

        manager.failCard(key, "rate limited");

        // last content push carries the failure suffix
        assertTrue(manager.contentCalls.get(manager.contentCalls.size() - 1).content().contains("rate limited"));
        assertEquals(1, manager.closeCalls.size());

        // A subsequent finishCard must NOT trigger another close
        manager.finishCard(key, "ignored");
        assertEquals(1, manager.closeCalls.size(), "Second terminal call must be ignored");
    }

    @Test
    @DisplayName("failCard with no accumulator emits a stand-alone error message")
    void failCardWithEmptyAccumulator() {
        manager.fakeNowMs.set(0L);
        String key = manager.createAndDeliver(7L, "open_id", "ou_abc", null);

        manager.failCard(key, "network down");
        assertEquals(1, manager.contentCalls.size());
        assertTrue(manager.contentCalls.get(0).content().contains("network down"));
    }

    @Test
    @DisplayName("initial card JSON declares schema 2.0 + streaming_mode + the stream element id")
    @SuppressWarnings("unchecked")
    void initialCardJsonShape() {
        Map<String, Object> card = manager.buildInitialCardJson("test");
        assertEquals("2.0", card.get("schema"));
        Map<String, Object> config = (Map<String, Object>) card.get("config");
        assertEquals(Boolean.TRUE, config.get("streaming_mode"));
        Map<String, Object> body = (Map<String, Object>) card.get("body");
        List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
        assertEquals(FeishuStreamingCardManager.STREAM_ELEMENT_ID, elements.get(0).get("element_id"));
        assertEquals("markdown", elements.get(0).get("tag"));
    }

    @Test
    @DisplayName("unknown session key is a silent no-op on every public method")
    void unknownSessionIsNoop() {
        manager.appendContent("never-existed", "x", true);
        manager.finishCard("never-existed", "x");
        manager.failCard("never-existed", "x");
        assertEquals(0, manager.contentCalls.size());
        assertEquals(0, manager.closeCalls.size());
        assertFalse(false);  // assertion just to make junit happy with no real check
    }
}
