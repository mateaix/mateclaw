package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.wecom.cards.WeComCardDispatcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the chunk-content dedup added to
 * {@link WeComChannelAdapter#replyStream(String, String, String, boolean, String)}
 * (RFC-32 §2.1.3). Without dedup, every token-level update during tool
 * argument streaming would emit a fresh frame even when the visible
 * content didn't change — flickering the IM client.
 *
 * <p>Run pattern: drop {@code sendFrame} into a queue so we can count
 * how many frames actually went out for a given content sequence,
 * without touching a real WebSocket.
 */
class ReplyStreamDedupTest {

    private TestableAdapter adapter;
    private LinkedBlockingQueue<Map<String, Object>> sentFrames;

    @BeforeEach
    void setUp() throws Exception {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("wecom");
        entity.setConfigJson("{}");
        adapter = new TestableAdapter(
                entity,
                Mockito.mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                Mockito.mock(ApprovalNotificationService.class),
                Mockito.mock(WeComCardDispatcher.class),
                Mockito.mock(WeComKeepaliveScheduler.class));
        sentFrames = adapter.sentFrames;

        // Bring the adapter to "running + accepting" so sendFrameWithAck doesn't
        // fast-fail on the lifecycle gate (PR-0).
        Field running = adapter.getClass().getSuperclass().getSuperclass().getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(adapter)).set(true);
        Method ensure = WeComChannelAdapter.class.getDeclaredMethod("ensureReplyExecutor");
        ensure.setAccessible(true);
        ensure.invoke(adapter);
        Method open = WeComChannelAdapter.class.getDeclaredMethod("openReplyQueue");
        open.setAccessible(true);
        open.invoke(adapter);
        // Long idle so the worker doesn't churn during the short test.
        adapter.workerIdleTimeoutMs = 60_000L;
    }

    @Test
    @DisplayName("identical non-final chunks dedup: only first goes out")
    void identicalChunksDedup() throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "replyStream", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(adapter, "rid", "stream-1", "Hello", false);
        m.invoke(adapter, "rid", "stream-1", "Hello", false);  // dup → skipped
        m.invoke(adapter, "rid", "stream-1", "Hello", false);  // dup → skipped

        // Only the first frame should have been dispatched (give worker a beat).
        Map<String, Object> first = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        assertNotNull(first, "first non-final chunk should have dispatched");
        assertNull(sentFrames.poll(200, TimeUnit.MILLISECONDS),
                "duplicate non-final chunks must be deduplicated");
    }

    @Test
    @DisplayName("changed content always goes out")
    void changedContentDispatches() throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "replyStream", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(adapter, "rid", "stream-1", "Hello", false);
        m.invoke(adapter, "rid", "stream-1", "Hello world", false);  // changed → goes
        m.invoke(adapter, "rid", "stream-1", "Hello world", false);  // dup → skipped

        // 2 frames expected (poll up to 500ms each)
        Map<String, Object> f1 = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        Map<String, Object> f2 = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        assertNotNull(f1);
        assertNotNull(f2);
        assertNull(sentFrames.poll(200, TimeUnit.MILLISECONDS),
                "no third frame: only 2 distinct contents should have been sent");
    }

    @Test
    @DisplayName("finish=true always goes out, even with identical content")
    void finishAlwaysDispatches() throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "replyStream", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(adapter, "rid", "stream-1", "Done", false);
        m.invoke(adapter, "rid", "stream-1", "Done", true);  // SAME content but finish=true → goes

        Map<String, Object> f1 = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        Map<String, Object> f2 = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        assertNotNull(f1);
        assertNotNull(f2, "finish=true must always dispatch even when content matches the previous chunk");
    }

    @Test
    @DisplayName("dedup is per-streamId; different streams don't interfere")
    void perStreamIsolation() throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "replyStream", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(adapter, "rid", "stream-A", "X", false);
        m.invoke(adapter, "rid", "stream-B", "X", false);  // different stream — must dispatch

        Map<String, Object> f1 = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        Map<String, Object> f2 = sentFrames.poll(500, TimeUnit.MILLISECONDS);
        assertNotNull(f1);
        assertNotNull(f2,
                "dedup memory must be per-streamId — same content on a different stream still dispatches");
    }

    @Test
    @DisplayName("after finish=true, the dedup slot is cleared so the next stream with same content goes")
    void finishClearsDedupSlot() throws Exception {
        Method m = WeComChannelAdapter.class.getDeclaredMethod(
                "replyStream", String.class, String.class, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(adapter, "rid", "stream-1", "X", false);
        m.invoke(adapter, "rid", "stream-1", "X", true);  // finish, clears slot
        m.invoke(adapter, "rid", "stream-1", "X", false); // new chunk — slot was cleared, so goes

        // 3 frames expected total
        for (int i = 0; i < 3; i++) {
            assertNotNull(sentFrames.poll(500, TimeUnit.MILLISECONDS),
                    "expected frame #" + (i + 1) + " to dispatch");
        }
    }

    /**
     * Test-only adapter that captures dispatched frames AND auto-completes
     * each {@code pendingAcks} future shortly after the frame goes out, so
     * the per-reqId serial worker can dequeue the next task without waiting
     * the full 5s {@code orTimeout}. Without auto-ack, the dedup tests that
     * dispatch multiple distinct frames would each block ~5s on the prior
     * frame's ACK.
     */
    static class TestableAdapter extends WeComChannelAdapter {
        final LinkedBlockingQueue<Map<String, Object>> sentFrames = new LinkedBlockingQueue<>();
        private static final ExecutorService AUTOACK = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-autoack-dedup");
            t.setDaemon(true);
            return t;
        });

        TestableAdapter(ChannelEntity entity, ChannelMessageRouter router,
                        ObjectMapper mapper, ApprovalNotificationService approvalSvc,
                        WeComCardDispatcher cardDispatcher, WeComKeepaliveScheduler keepalive) {
            super(entity, router, mapper, approvalSvc, cardDispatcher, keepalive);
        }

        @Override
        @SuppressWarnings("unchecked")
        void sendFrame(Map<String, Object> frame) {
            sentFrames.offer(frame);
            // Mirror what the WeCom server would do in production: ACK the
            // outbound request so the worker's task.future().join() unblocks
            // and the next frame in the same reqId queue can dispatch.
            Map<String, Object> headers = (Map<String, Object>) frame.get("headers");
            if (headers == null) return;
            String reqId = (String) headers.get("req_id");
            if (reqId == null || reqId.isBlank()) return;
            AUTOACK.submit(() -> completeAckSoon(reqId));
        }

        private void completeAckSoon(String reqId) {
            try {
                // Brief delay so the worker has reliably completed
                // pendingAcks.put before we look it up.
                Thread.sleep(2);
                Field f = WeComChannelAdapter.class.getDeclaredField("pendingAcks");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending =
                        (ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>>) f.get(this);
                CompletableFuture<Map<String, Object>> fut = pending.get(reqId);
                if (fut != null) fut.complete(Map.of("errcode", 0));
            } catch (Exception ignored) {}
        }
    }
}
