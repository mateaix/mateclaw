package vip.mate.channel.wecom;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Periodically refreshes a WeCom AI Bot {@code stream} reply with the
 * "🤔 思考中..." placeholder text so WeCom's server-side does not drop
 * the stream slot while a long-running agent task is still computing.
 *
 * <p><b>Why this exists</b>: WeCom's stream slot has an undocumented TTL
 * (empirically observed at ~60-120s of silence). When the slot drops,
 * the eventual {@code finish=true} chunk is silently rejected — the
 * user sees "🤔 思考中..." stuck forever. RFC-32 §2.1.2 (R-7 / B-5).
 *
 * <p><b>Constants</b> (chosen empirically based on observed slot lifetime):
 * <ul>
 *   <li>20s refresh interval — well under the observed 60s minimum drop</li>
 *   <li>180s force-finish ceiling — bound the worst-case "stuck" UX even
 *       if the agent task hangs forever; lets the next user reply use a
 *       fresh stream slot rather than reuse a closed one</li>
 * </ul>
 *
 * <p><b>Force-finish invariant</b>: after the 180s ceiling fires, the
 * scheduler calls {@link WeComChannelAdapter#invalidateReplyContext}
 * to evict the {@code (frameReqId, processingStreamId)} pair from
 * {@code replyContexts} — so when the eventual real reply arrives,
 * {@code renderAndSend} sees no context and falls through to a fresh
 * {@code sendMessage} path instead of reusing the dead stream id.
 * RFC-32 §2.1.2 / R-7 closes this race; the adapter's
 * {@code invalidateReplyContext} is the contract.
 */
@Slf4j
@Component
public class WeComKeepaliveScheduler {

    /** Refresh interval (seconds). */
    static final long REFRESH_INTERVAL_SECONDS = 20;

    /** Hard ceiling — after this many seconds, force-finish the stream. */
    static final long MAX_DURATION_SECONDS = 180;

    /** Placeholder text written on every refresh tick (when no live progress supplier is attached). */
    static final String PROCESSING_TEXT = "🤔 思考中...";

    /**
     * Text written when the 180s ceiling force-finishes the stream. The real
     * answer will arrive later as a separate pushed bubble (the reply context
     * is invalidated below), so the sealed bubble must tell the user the
     * reply is still coming — freezing it on "思考中..." reads as a hang.
     */
    static final String FORCE_FINISH_TEXT = "⏳ 任务耗时较长，仍在处理中，结果稍后送达";

    /** One-shot state per active stream. Held by reference inside the scheduled task. */
    private static final class StreamState {
        final WeComChannelAdapter adapter;
        final String reqId;
        final String streamId;
        final String replyToken;
        final long startedAt;
        volatile ScheduledFuture<?> future;
        /** Optional live progress text source; when set, refresh ticks write
         *  its current snapshot instead of the static placeholder so the
         *  bubble keeps showing elapsed time / tool state between events. */
        volatile Supplier<String> textSupplier;
        StreamState(WeComChannelAdapter a, String r, String s, String t) {
            this.adapter = a; this.reqId = r; this.streamId = s; this.replyToken = t;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final Map<String, StreamState> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "wecom-keepalive");
        t.setDaemon(true);
        return t;
    });

    /**
     * Begin keepalive for a freshly-issued processing stream. No-op if
     * called twice for the same {@code streamId} (just logs and keeps
     * the existing schedule alive).
     *
     * @param adapter   the live WeCom adapter (carries replyStream + invalidateReplyContext)
     * @param reqId     the inbound message frame's req_id (binds the
     *                  outbound reply_stream chunks)
     * @param streamId  the same stream_id used in the initial "🤔 思考中..." chunk
     * @param replyToken target id used to look up reply context on
     *                  invalidation (typically chatId for groups,
     *                  senderId for direct messages — same value passed
     *                  to {@code replyContexts.put})
     */
    public void start(WeComChannelAdapter adapter, String reqId, String streamId, String replyToken) {
        if (adapter == null || reqId == null || streamId == null
                || reqId.isBlank() || streamId.isBlank()) {
            log.debug("[wecom-keepalive] start ignored — null/blank arg(s)");
            return;
        }
        if (states.containsKey(streamId)) {
            log.debug("[wecom-keepalive] start ignored — stream {} already tracked", streamId);
            return;
        }
        StreamState st = new StreamState(adapter, reqId, streamId, replyToken);
        st.future = scheduler.scheduleAtFixedRate(
                () -> tick(st),
                REFRESH_INTERVAL_SECONDS,
                REFRESH_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
        states.put(streamId, st);
        log.debug("[wecom-keepalive] started for stream={} reqId={}", streamId, reqId);
    }

    /**
     * Attach a live progress text source to an already-tracked stream.
     * Subsequent refresh ticks write the supplier's snapshot instead of the
     * static placeholder. No-op when the stream is not tracked (already
     * stopped or force-finished).
     */
    public void attachTextSupplier(String streamId, Supplier<String> supplier) {
        if (streamId == null || streamId.isBlank()) {
            return;
        }
        StreamState st = states.get(streamId);
        if (st != null) {
            st.textSupplier = supplier;
        }
    }

    /**
     * Stop keepalive for a stream — call this immediately before sending
     * the real reply so the next refresh tick doesn't race the
     * {@code finish=true} chunk on the same stream.
     */
    public void stop(String streamId) {
        if (streamId == null || streamId.isBlank()) return;
        StreamState st = states.remove(streamId);
        if (st != null && st.future != null) {
            st.future.cancel(false);
            log.debug("[wecom-keepalive] stopped for stream={}", streamId);
        }
    }

    /**
     * Drop every tracked stream and cancel its schedule. Called from
     * {@code releaseConnectionResources} so reconnects start clean.
     */
    public void shutdownAll() {
        for (StreamState st : states.values()) {
            if (st.future != null) st.future.cancel(false);
        }
        states.clear();
    }

    private void tick(StreamState st) {
        long elapsedSec = (System.currentTimeMillis() - st.startedAt) / 1000;
        if (elapsedSec >= MAX_DURATION_SECONDS) {
            // Force-finish: send finish=true on the same stream so the
            // server-side closes the slot cleanly, then evict the
            // replyContext entry so the eventual real reply takes the
            // fresh-stream path.
            try {
                st.adapter.replyStreamFinishForKeepalive(st.reqId, st.streamId, FORCE_FINISH_TEXT);
            } catch (Exception e) {
                log.debug("[wecom-keepalive] force-finish replyStream failed for {}: {}",
                        st.streamId, e.getMessage());
            }
            try {
                st.adapter.invalidateReplyContext(st.replyToken, st.streamId);
            } catch (Exception e) {
                log.debug("[wecom-keepalive] invalidateReplyContext failed for {}: {}",
                        st.streamId, e.getMessage());
            }
            stop(st.streamId);
            log.info("[wecom-keepalive] force-finished stream {} after {}s ceiling",
                    st.streamId, MAX_DURATION_SECONDS);
            return;
        }
        try {
            st.adapter.replyStreamRefreshForKeepalive(st.reqId, st.streamId, refreshText(st));
        } catch (Exception e) {
            log.debug("[wecom-keepalive] refresh failed for {}: {}", st.streamId, e.getMessage());
        }
    }

    /** Current refresh text: live progress snapshot when attached, static placeholder otherwise. */
    private String refreshText(StreamState st) {
        Supplier<String> supplier = st.textSupplier;
        if (supplier != null) {
            try {
                String text = supplier.get();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            } catch (Exception e) {
                log.debug("[wecom-keepalive] progress supplier failed for {}: {}",
                        st.streamId, e.getMessage());
            }
        }
        return PROCESSING_TEXT;
    }

    // ---- Test hooks ----

    int activeStreamCount() { return states.size(); }
}
