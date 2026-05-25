package vip.mate.channel.wecom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verify the WeComKeepaliveScheduler bookkeeping + force-finish path.
 *
 * <p>The 20s/180s timing constants come from QwenPaw and are already
 * validated empirically in production; we don't re-test the exact
 * scheduling intervals here (would require either real wall-clock waits
 * or invasive ScheduledExecutor mocking). Instead we cover:
 * <ul>
 *   <li>start/stop/shutdownAll bookkeeping is correct</li>
 *   <li>the force-finish branch (180s ceiling) calls
 *       {@link WeComChannelAdapter#replyStreamFinishForKeepalive} AND
 *       {@link WeComChannelAdapter#invalidateReplyContext} — the
 *       RFC-32 §2.1.2 invariant that prevents the next real reply from
 *       reusing a closed stream slot</li>
 *   <li>the refresh branch (still under ceiling) calls
 *       {@link WeComChannelAdapter#replyStreamRefreshForKeepalive} only</li>
 * </ul>
 *
 * <p>Force-finish is exercised by reflection-overriding {@code startedAt}
 * to a long-ago timestamp on a tracked StreamState, then invoking the
 * private {@code tick} method. This bypasses the ScheduledExecutor
 * entirely so tests run in milliseconds.
 */
class WeComKeepaliveSchedulerTest {

    private WeComKeepaliveScheduler scheduler;
    private WeComChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        scheduler = new WeComKeepaliveScheduler();
        adapter = Mockito.mock(WeComChannelAdapter.class);
    }

    @Test
    @DisplayName("start adds a stream entry; stop removes it")
    void startStopBookkeeping() {
        assertEquals(0, scheduler.activeStreamCount());

        scheduler.start(adapter, "req-1", "stream-1", "user-alice");
        assertEquals(1, scheduler.activeStreamCount());

        scheduler.stop("stream-1");
        assertEquals(0, scheduler.activeStreamCount());
    }

    @Test
    @DisplayName("start is idempotent — second call for same streamId is a no-op")
    void startIdempotent() {
        scheduler.start(adapter, "req-1", "stream-1", "user-alice");
        scheduler.start(adapter, "req-1", "stream-1", "user-alice");
        assertEquals(1, scheduler.activeStreamCount(), "second start must not double-track");
    }

    @Test
    @DisplayName("start is null-tolerant — null/blank args silently drop")
    void startNullTolerant() {
        scheduler.start(null, "r", "s", "t");
        scheduler.start(adapter, null, "s", "t");
        scheduler.start(adapter, "", "s", "t");
        scheduler.start(adapter, "r", null, "t");
        scheduler.start(adapter, "r", "", "t");
        assertEquals(0, scheduler.activeStreamCount(),
                "null/blank args must not add entries");
    }

    @Test
    @DisplayName("shutdownAll clears every tracked stream")
    void shutdownAllClears() {
        scheduler.start(adapter, "req-1", "stream-1", "user-alice");
        scheduler.start(adapter, "req-2", "stream-2", "user-bob");
        assertEquals(2, scheduler.activeStreamCount());

        scheduler.shutdownAll();
        assertEquals(0, scheduler.activeStreamCount());
    }

    @Test
    @DisplayName("force-finish path: replyStreamFinishForKeepalive + invalidateReplyContext + stop")
    void forceFinishPath() throws Exception {
        scheduler.start(adapter, "req-x", "stream-x", "user-alice");

        // Reflectively rewind startedAt so the next tick sees elapsed > 180s
        Object state = getStreamState("stream-x");
        Field startedAt = state.getClass().getDeclaredField("startedAt");
        startedAt.setAccessible(true);
        // Java's `final long` fields normally resist setAccessible.set — unfortunately
        // primitives also need the modifiers hack on JDK 17+. Use Unsafe-free path:
        // the field happens to be declared `final` in the static record, so we mutate
        // via setLong (which works for primitives even on final fields when accessible
        // is true on JDK17 — verified locally).
        startedAt.setLong(state, System.currentTimeMillis() - 200_000L);

        // Manually invoke the private tick(StreamState) — no ScheduledExecutor
        // wall-clock wait
        Method tick = WeComKeepaliveScheduler.class.getDeclaredMethod(
                "tick", Class.forName(WeComKeepaliveScheduler.class.getName() + "$StreamState"));
        tick.setAccessible(true);
        tick.invoke(scheduler, state);

        verify(adapter, times(1)).replyStreamFinishForKeepalive(
                eq("req-x"), eq("stream-x"), eq(WeComKeepaliveScheduler.PROCESSING_TEXT));
        verify(adapter, times(1)).invalidateReplyContext(eq("user-alice"), eq("stream-x"));
        verify(adapter, never()).replyStreamRefreshForKeepalive(any(), any(), any());
        // After force-finish, the stream is removed from the tracker
        assertEquals(0, scheduler.activeStreamCount());
    }

    @Test
    @DisplayName("refresh path: replyStreamRefreshForKeepalive only — no force-finish below ceiling")
    void refreshPathBelowCeiling() throws Exception {
        scheduler.start(adapter, "req-y", "stream-y", "user-bob");

        // Don't rewind startedAt; the state is fresh — well under 180s.
        Object state = getStreamState("stream-y");
        Method tick = WeComKeepaliveScheduler.class.getDeclaredMethod(
                "tick", Class.forName(WeComKeepaliveScheduler.class.getName() + "$StreamState"));
        tick.setAccessible(true);
        tick.invoke(scheduler, state);

        verify(adapter, times(1)).replyStreamRefreshForKeepalive(
                eq("req-y"), eq("stream-y"), eq(WeComKeepaliveScheduler.PROCESSING_TEXT));
        verify(adapter, never()).replyStreamFinishForKeepalive(any(), any(), any());
        verify(adapter, never()).invalidateReplyContext(any(), any());
        // Still tracked — refresh ticks don't unregister
        assertEquals(1, scheduler.activeStreamCount());
    }

    @Test
    @DisplayName("constants match the QwenPaw-verified values (20s refresh / 180s ceiling)")
    void constantsMatch() {
        assertEquals(20L, WeComKeepaliveScheduler.REFRESH_INTERVAL_SECONDS);
        assertEquals(180L, WeComKeepaliveScheduler.MAX_DURATION_SECONDS);
        assertEquals("🤔 思考中...", WeComKeepaliveScheduler.PROCESSING_TEXT);
    }

    // Pull a tracked StreamState by streamId via reflection. The states map
    // lives behind a private final ConcurrentHashMap.
    private Object getStreamState(String streamId) throws Exception {
        Field statesField = WeComKeepaliveScheduler.class.getDeclaredField("states");
        statesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) statesField.get(scheduler);
        Object st = states.get(streamId);
        assertNotNull(st, "expected stream " + streamId + " to be tracked");
        return st;
    }
}
