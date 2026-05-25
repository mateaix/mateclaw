package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.wecom.cards.WeComCardDispatcher;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.WebSocket;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-32 §3.0 PR-0 stress catalog — six tests covering every concurrency
 * race the v2.0~v2.5.1 review chain identified.
 *
 * <p>Each test runs against a {@link TestableAdapter} that overrides
 * {@code sendFrame} so no real WebSocket is touched. Other state
 * (running flag, lifecycle gate, pendingAcks map) is poked via
 * reflection — keeping production-code visibility tweaks to a minimum
 * (just {@code workerIdleTimeoutMs} and dropping {@code private} from
 * {@code sendFrame}).
 *
 * <p>None of these tests sleep more than ~3s total even at high
 * iteration counts, so they're safe to run in regular CI rather than
 * a separate stress-only profile.
 */
class ReplyQueueStressTest {

    private TestableAdapter adapter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setName("test-wecom");
        entity.setChannelType("wecom");
        entity.setConfigJson("{}");
        ChannelMessageRouter router = Mockito.mock(ChannelMessageRouter.class);
        ApprovalNotificationService approvalSvc = Mockito.mock(ApprovalNotificationService.class);
        WeComCardDispatcher cardDispatcher = Mockito.mock(WeComCardDispatcher.class);
        WeComKeepaliveScheduler keepalive = Mockito.mock(WeComKeepaliveScheduler.class);
        mapper = new ObjectMapper();
        adapter = new TestableAdapter(entity, router, mapper, approvalSvc, cardDispatcher, keepalive);
        // Manually bring the adapter to a "running and ready" state without
        // doing a real WS handshake. This is what doStart + connectWebSocket +
        // markReady would have produced on a live system.
        setRunning(adapter, true);
        invokePrivate(adapter, "ensureReplyExecutor");
        invokePrivate(adapter, "openReplyQueue");
        // Most tests run with a much shorter idle timeout so the worker's
        // 60-second poll doesn't dominate test wall-clock time.
        adapter.workerIdleTimeoutMs = 80;
    }

    @AfterEach
    void tearDown() throws Exception {
        // Belt-and-suspenders cleanup: even if an assertion failed, drop
        // the executor so dangling worker threads don't bleed into the
        // next test.
        try {
            invokePrivate(adapter, "releaseConnectionResources", new Class<?>[]{String.class}, "test-teardown");
        } catch (Exception ignored) {}
        setRunning(adapter, false);
    }

    // =====================================================================
    // S-1: same reqId serial dispatch
    // =====================================================================

    @Nested
    @DisplayName("S-1 same reqId serial dispatch")
    class S1_SerialDispatch {

        @Test
        @DisplayName("three frames on same reqId: only one in flight at a time")
        void serialPerReqId() throws Exception {
            String reqId = "req_s1";
            // Don't auto-ACK; tests will release ACKs one by one.
            adapter.autoAck = false;

            CompletableFuture<Map<String, Object>> f1 = adapter.callSendFrameWithAck(reqId, frame(reqId, "msg1"));
            CompletableFuture<Map<String, Object>> f2 = adapter.callSendFrameWithAck(reqId, frame(reqId, "msg2"));
            CompletableFuture<Map<String, Object>> f3 = adapter.callSendFrameWithAck(reqId, frame(reqId, "msg3"));

            // Worker thread starts asynchronously — give it a tick to dequeue
            // the first task and dispatch sendFrame.
            assertEquals("msg1", awaitFrameText(adapter, 500),
                    "first frame must dispatch within 500ms");

            // No further frame may dispatch until the first ACK arrives.
            // Sleep ~150ms (≈ 2x adapter.workerIdleTimeoutMs) and assert
            // the queue stayed empty.
            Thread.sleep(150);
            assertNull(adapter.sentFrames.poll(), "second frame must NOT dispatch before first ACK");

            // Release ACK 1 → frame 2 should now dispatch.
            completeAck(adapter, reqId);
            assertEquals("msg2", awaitFrameText(adapter, 500));

            Thread.sleep(150);
            assertNull(adapter.sentFrames.poll(), "third frame must NOT dispatch before second ACK");

            completeAck(adapter, reqId);
            assertEquals("msg3", awaitFrameText(adapter, 500));

            completeAck(adapter, reqId);

            // All three futures should now complete successfully.
            assertNotNull(f1.get(500, TimeUnit.MILLISECONDS));
            assertNotNull(f2.get(500, TimeUnit.MILLISECONDS));
            assertNotNull(f3.get(500, TimeUnit.MILLISECONDS));
        }
    }

    // =====================================================================
    // S-2: sendFrame sync throw → future fails immediately
    // =====================================================================

    @Nested
    @DisplayName("S-2 sendFrame sync throw → future fails fast")
    class S2_SendFrameThrow {

        @Test
        @DisplayName("future fails within 200ms on IOException, not the 5s ACK timeout")
        void syncThrowFailsFast() throws Exception {
            adapter.sendFrameBehavior = frame -> {
                throw new RuntimeException("simulated ws sendText failure", new IOException("ws null"));
            };

            long t0 = System.nanoTime();
            CompletableFuture<Map<String, Object>> future =
                    adapter.callSendFrameWithAck("req_s2", frame("req_s2", "x"));

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> future.get(500, TimeUnit.MILLISECONDS),
                    "future must complete (exceptionally) within 500ms");
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 200,
                    "should fail-fast in under 200ms, took " + elapsedMs + "ms");
            assertNotNull(ex.getCause());
        }
    }

    // =====================================================================
    // S-3: idle-close vs late-enqueue race × many iterations × many threads
    // =====================================================================

    @Nested
    @DisplayName("S-3 worker idle-close vs late enqueue: no orphans across N iterations")
    class S3_IdleRace {

        @Test
        @DisplayName("100 iterations × 8 threads: every offered task completes")
        void noOrphansUnderRace() throws Exception {
            // Tighten idle timeout to 30ms so each iteration cycles through
            // open → busy → idle-close in the low-100ms range.
            adapter.workerIdleTimeoutMs = 30;
            adapter.autoAck = true;  // ACK as soon as worker dispatches

            int threads = 8;
            int iterationsPerThread = 100;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(1);
            ConcurrentLinkedQueue<CompletableFuture<Map<String, Object>>> all =
                    new ConcurrentLinkedQueue<>();

            for (int t = 0; t < threads; t++) {
                final int tid = t;
                pool.submit(() -> {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < iterationsPerThread; i++) {
                        // Mix reqIds: some shared (forces worker reuse), some
                        // unique (forces fresh-state path).
                        String reqId = (i % 3 == 0)
                                ? "shared_req"
                                : "t" + tid + "_i" + i;
                        all.add(adapter.callSendFrameWithAck(reqId, frame(reqId, "p")));
                        // Random tiny delay so worker idle-close has a chance
                        // to interleave with late offers.
                        if (i % 10 == 0) {
                            try { Thread.sleep(35); } catch (InterruptedException ie) { return; }
                        }
                    }
                });
            }
            latch.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "submission threads must finish");

            // Every offered future must eventually complete. 5s budget for the
            // worker(s) to drain. Track failures with reasons for debuggability.
            int total = all.size();
            int orphans = 0;
            int succeeded = 0;
            int failed = 0;
            long deadline = System.currentTimeMillis() + 5_000;
            for (CompletableFuture<Map<String, Object>> f : all) {
                long remaining = Math.max(0, deadline - System.currentTimeMillis());
                try {
                    f.get(remaining, TimeUnit.MILLISECONDS);
                    succeeded++;
                } catch (TimeoutException te) {
                    orphans++;
                } catch (Exception e) {
                    // ExecutionException or interrupt — counted as completed
                    // (test only cares that no future hangs forever).
                    failed++;
                }
            }
            assertEquals(0, orphans,
                    "no future may remain pending after the queue drains; " +
                            "total=" + total + " ok=" + succeeded + " err=" + failed +
                            " orphans=" + orphans);
        }
    }

    // =====================================================================
    // S-4: release in progress → all enqueues fast-fail
    // =====================================================================

    @Nested
    @DisplayName("S-4 release window: enqueues fail fast, no orphans")
    class S4_ReleaseRace {

        @Test
        @DisplayName("100 concurrent enqueues during release: all complete in <1s")
        void releaseFailsFast() throws Exception {
            // Spawn 100 concurrent enqueues. Halfway through, trigger
            // releaseConnectionResources on a separate thread.
            int N = 100;
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            ConcurrentLinkedQueue<CompletableFuture<Map<String, Object>>> futures =
                    new ConcurrentLinkedQueue<>();

            for (int i = 0; i < N; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException ignored) {}
                    futures.add(adapter.callSendFrameWithAck("req_s4_" + idx, frame("req_s4_" + idx, "p")));
                });
            }
            // Trigger release shortly after enqueue burst begins.
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                try {
                    Thread.sleep(5);  // a small lead so some enqueues land first
                    invokePrivate(adapter, "releaseConnectionResources",
                            new Class<?>[]{String.class}, "s4-test");
                } catch (Exception ignored) {}
            });
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            // Every future must complete in <1s — either success (offered
            // before gate closed and worker drained) or IllegalStateException
            // (gate closed by release).
            long t0 = System.nanoTime();
            int hangs = 0;
            for (CompletableFuture<Map<String, Object>> f : futures) {
                try {
                    f.get(1_000, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    hangs++;
                } catch (Exception ignored) {}
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(0, hangs, hangs + " future(s) hung during release window");
            assertTrue(elapsedMs < 2_000,
                    "all " + futures.size() + " futures should resolve in <2s, took " + elapsedMs + "ms");
        }
    }

    // =====================================================================
    // S-5: executor ready but markReady not called → fast-fail
    // =====================================================================

    @Nested
    @DisplayName("S-5 lifecycle gate: enqueue before markReady fails fast")
    class S5_GateClosed {

        @Test
        @DisplayName("with executor present but accepting=false, enqueue returns failed future immediately")
        void closedGateFailsFast() throws Exception {
            // Force the lifecycle into "executor ready, transport not ready"
            // (the exact window R-7 covers).
            adapter.workerIdleTimeoutMs = 60_000;  // restore to default — we don't want the worker pool churning
            // Take the gate down without going through release.
            Field gate = WeComChannelAdapter.class.getDeclaredField("replyQueueAccepting");
            gate.setAccessible(true);
            ((AtomicBoolean) gate.get(adapter)).set(false);

            long t0 = System.nanoTime();
            CompletableFuture<Map<String, Object>> f =
                    adapter.callSendFrameWithAck("req_s5", frame("req_s5", "x"));
            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> f.get(200, TimeUnit.MILLISECONDS));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(elapsedMs < 100,
                    "fast-fail should be near-instant (sync resolution), took " + elapsedMs + "ms");
            assertInstanceOf(IllegalStateException.class, ex.getCause(),
                    "must surface the gate-closed reason as IllegalStateException");
            assertTrue(ex.getCause().getMessage().contains("not accepting"),
                    "error message must mention 'not accepting'; got: " + ex.getCause().getMessage());
            // No frame should ever have been queued.
            assertNull(adapter.sentFrames.poll(),
                    "sendFrame must not be invoked when gate is closed");
        }
    }

    // =====================================================================
    // S-6: release ordering — accepting=false happens-before ws.close()
    // =====================================================================

    @Nested
    @DisplayName("S-6 release ordering: accepting flips first")
    class S6_ReleaseOrdering {

        @Test
        @DisplayName("when ws.sendClose runs, replyQueueAccepting is already false")
        void acceptingFalseBeforeWsClose() throws Exception {
            // Install an instrumented WebSocket that records the gate value
            // at the moment sendClose() is invoked.
            AtomicBoolean acceptingAtCloseTime = new AtomicBoolean(true);
            AtomicBoolean closeWasCalled = new AtomicBoolean(false);

            WebSocket fakeWs = (WebSocket) java.lang.reflect.Proxy.newProxyInstance(
                    WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class},
                    (proxy, method, args) -> {
                        if ("sendClose".equals(method.getName())) {
                            // Snapshot gate state at the exact moment release
                            // is calling close on us. The S-6 invariant:
                            // step 0 must have already flipped accepting.
                            Field gate = WeComChannelAdapter.class.getDeclaredField("replyQueueAccepting");
                            gate.setAccessible(true);
                            acceptingAtCloseTime.set(((AtomicBoolean) gate.get(adapter)).get());
                            closeWasCalled.set(true);
                            return CompletableFuture.completedFuture(proxy);
                        }
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == long.class) return 0L;
                        return null;
                    });

            // Inject the fake into the adapter and verify accepting is true
            // (i.e. we're in normal operation about to release).
            Field wsField = WeComChannelAdapter.class.getDeclaredField("webSocket");
            wsField.setAccessible(true);
            wsField.set(adapter, fakeWs);

            Field gate = WeComChannelAdapter.class.getDeclaredField("replyQueueAccepting");
            gate.setAccessible(true);
            assertTrue(((AtomicBoolean) gate.get(adapter)).get(),
                    "precondition: accepting must be true before release");

            invokePrivate(adapter, "releaseConnectionResources",
                    new Class<?>[]{String.class}, "s6-test");

            assertTrue(closeWasCalled.get(), "release must invoke ws.sendClose");
            assertFalse(acceptingAtCloseTime.get(),
                    "step 0 (accepting=false) must happen-before ws.sendClose; " +
                            "if this fails, the release method body has been re-ordered " +
                            "and an enqueue could land between accepting and ws teardown");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Build the canonical aibot_respond_msg frame the adapter uses. */
    private static Map<String, Object> frame(String reqId, String text) {
        return Map.of(
                "cmd", "aibot_respond_msg",
                "headers", Map.of("req_id", reqId),
                "body", Map.of("msgtype", "text", "text", Map.of("content", text))
        );
    }

    /** Read the most-recent dispatched frame's text content. Polls up to {@code timeoutMs}. */
    private static String awaitFrameText(TestableAdapter a, long timeoutMs) throws Exception {
        Map<String, Object> f = a.sentFrames.poll(timeoutMs, TimeUnit.MILLISECONDS);
        assertNotNull(f, "no frame dispatched within " + timeoutMs + "ms");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) f.get("body");
        @SuppressWarnings("unchecked")
        Map<String, Object> txt = (Map<String, Object>) body.get("text");
        return (String) txt.get("content");
    }

    /** Complete the in-flight ACK future for the given reqId. Returns true if found. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean completeAck(WeComChannelAdapter a, String reqId) throws Exception {
        Field f = WeComChannelAdapter.class.getDeclaredField("pendingAcks");
        f.setAccessible(true);
        ConcurrentHashMap<String, CompletableFuture> map =
                (ConcurrentHashMap<String, CompletableFuture>) f.get(a);
        // Wait briefly for the worker to register the future before completing.
        long deadline = System.currentTimeMillis() + 500;
        CompletableFuture future = null;
        while (System.currentTimeMillis() < deadline) {
            future = map.get(reqId);
            if (future != null) break;
            Thread.sleep(5);
        }
        if (future == null) return false;
        future.complete(Map.of("errcode", 0));
        return true;
    }

    private static void setRunning(WeComChannelAdapter a, boolean v) throws Exception {
        // running lives on AbstractChannelAdapter; walk the class chain to find it.
        Field running = findField(a.getClass(), "running");
        ((AtomicBoolean) running.get(a)).set(v);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(name + " not found in class chain rooted at " + cls);
    }

    private static Object invokePrivate(WeComChannelAdapter a, String method) throws Exception {
        return invokePrivate(a, method, new Class<?>[0]);
    }

    private static Object invokePrivate(WeComChannelAdapter a, String method,
                                        Class<?>[] paramTypes, Object... args) throws Exception {
        var m = WeComChannelAdapter.class.getDeclaredMethod(method, paramTypes);
        m.setAccessible(true);
        return m.invoke(a, args);
    }

    /**
     * Test-only adapter that captures dispatched frames and lets each
     * test choose between auto-ACK or manual ACK release.
     */
    static class TestableAdapter extends WeComChannelAdapter {

        final LinkedBlockingQueue<Map<String, Object>> sentFrames = new LinkedBlockingQueue<>();

        /** When false, tests must manually call {@code completeAck}. */
        volatile boolean autoAck = true;

        /** Optional behavior injected per-test (return value ignored; thrown exceptions propagate). */
        volatile Function<Map<String, Object>, Void> sendFrameBehavior = null;

        TestableAdapter(ChannelEntity entity, ChannelMessageRouter router,
                        ObjectMapper mapper, ApprovalNotificationService approvalSvc,
                        WeComCardDispatcher cardDispatcher, WeComKeepaliveScheduler keepalive) {
            super(entity, router, mapper, approvalSvc, cardDispatcher, keepalive);
        }

        @Override
        void sendFrame(Map<String, Object> frame) {
            sentFrames.offer(frame);
            Function<Map<String, Object>, Void> beh = sendFrameBehavior;
            if (beh != null) {
                beh.apply(frame);  // may throw
                return;
            }
            if (autoAck) {
                String reqId = extractReqId(frame);
                if (reqId != null) {
                    // Schedule async ACK on a tiny delay so the worker has time
                    // to register the future before we complete it.
                    AUTOACK.submit(() -> {
                        try {
                            Thread.sleep(2);
                            completeAck(this, reqId);
                        } catch (Exception ignored) {}
                    });
                }
            }
        }

        /** Expose package-private sendFrameWithAck to tests. */
        CompletableFuture<Map<String, Object>> callSendFrameWithAck(String reqId, Map<String, Object> frame) {
            try {
                var m = WeComChannelAdapter.class.getDeclaredMethod("sendFrameWithAck", String.class, Map.class);
                m.setAccessible(true);
                @SuppressWarnings("unchecked")
                CompletableFuture<Map<String, Object>> f =
                        (CompletableFuture<Map<String, Object>>) m.invoke(this, reqId, frame);
                return f;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private static String extractReqId(Map<String, Object> frame) {
            Map<String, Object> headers = (Map<String, Object>) frame.get("headers");
            return headers == null ? null : (String) headers.get("req_id");
        }

        private static final ExecutorService AUTOACK = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-auto-ack");
            t.setDaemon(true);
            return t;
        });
    }
}
