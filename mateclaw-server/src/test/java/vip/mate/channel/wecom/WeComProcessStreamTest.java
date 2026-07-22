package vip.mate.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.wecom.cards.WeComCardDispatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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
 * Exercises {@link WeComChannelAdapter#processStream}: the progress-bubble
 * path (event-driven reply_stream overwrites + final finish=true), the
 * degraded accumulate-then-send path, and the standalone tool-trace messages
 * gated by {@code filter_tool_messages=false}.
 */
class WeComProcessStreamTest {

    @Test
    @DisplayName("progress path overwrites the bubble with tool progress, then finishes with the answer")
    void progressPathStreamsAndFinishes() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                new StreamDelta(null, "先查时间"),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "get_time")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "get_time", "success", true)),
                new StreamDelta("现在是", null),
                new StreamDelta("下午三点。", null));

        String result = adapter.processStream(stream, inbound("alice"), "wecom:alice");

        assertEquals("现在是下午三点。", result);
        List<Map<String, Object>> frames = adapter.drainFrames();
        List<Map<String, Object>> streamBodies = streamBodies(frames);
        assertFalse(streamBodies.isEmpty(), "expected reply_stream progress frames");

        boolean sawToolProgress = streamBodies.stream()
                .filter(s -> Boolean.FALSE.equals(s.get("finish")))
                .anyMatch(s -> String.valueOf(s.get("content")).contains("get_time"));
        assertTrue(sawToolProgress, "some non-final chunk should show the tool call");

        Map<String, Object> finalChunk = streamBodies.get(streamBodies.size() - 1);
        assertEquals(Boolean.TRUE, finalChunk.get("finish"), "last chunk must close the stream");
        assertTrue(String.valueOf(finalChunk.get("content")).contains("现在是下午三点。"));
    }

    @Test
    @DisplayName("stage narrations roll the bubble: each stage finishes its own bubble, final answer excludes them")
    void stageNarrationsRollBubbles() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.segmentOnly("我先查一下当前时间：", null),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "get_time")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "get_time", "success", true)),
                StreamDelta.segmentOnly("时间拿到了，再查会议室：", null),
                new StreamDelta("1 号会议室空闲，已预约。", null));

        String result = adapter.processStream(stream, inbound("alice"), "wecom:alice");

        // Narrations are excluded from the returned (persisted) final answer.
        assertEquals("1 号会议室空闲，已预约。", result);

        List<Map<String, Object>> streamBodies = streamBodies(adapter.drainFrames());
        List<Map<String, Object>> finished = streamBodies.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("finish"))).toList();
        // Three finished bubbles in chronological order: narration #1,
        // narration #2, final answer — each on its own stream id.
        assertEquals(3, finished.size(), "each stage plus the final answer closes one bubble");
        assertTrue(String.valueOf(finished.get(0).get("content")).contains("我先查一下当前时间"));
        assertTrue(String.valueOf(finished.get(1).get("content")).contains("再查会议室"));
        assertTrue(String.valueOf(finished.get(2).get("content")).contains("已预约"));
        assertEquals(3, finished.stream().map(s -> s.get("id")).distinct().count(),
                "each finished bubble must ride its own stream id");
        // The first narration finalizes the original placeholder stream.
        assertEquals("stream-1", finished.get(0).get("id"));
    }

    @Test
    @DisplayName("stream_progress=false degrades to accumulate-then-send with no interim overwrites")
    void progressDisabledDegrades() throws Exception {
        TestableAdapter adapter = newAdapter("{\"stream_progress\": false}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.event("tool_call_started", Map.of("toolCallId", "c1", "toolName", "t")),
                new StreamDelta("答案", null));

        String result = adapter.processStream(stream, inbound("alice"), "wecom:alice");

        assertEquals("答案", result);
        List<Map<String, Object>> streamBodies = streamBodies(adapter.drainFrames());
        // Only the final renderAndSend overwrite — no interim progress chunks.
        assertEquals(1, streamBodies.size(), "degraded path must not stream progress");
        assertEquals(Boolean.TRUE, streamBodies.get(0).get("finish"));
    }

    @Test
    @DisplayName("without a reply context the final answer goes out as a plain message")
    void noContextFallsBackToPlainSend() throws Exception {
        TestableAdapter adapter = newAdapter("{}");

        Flux<StreamDelta> stream = Flux.just(new StreamDelta("答案", null));
        String result = adapter.processStream(stream, inbound("alice"), "wecom:alice");

        assertEquals("答案", result);
        List<Map<String, Object>> frames = adapter.drainFrames();
        assertTrue(streamBodies(frames).isEmpty(), "no stream slot → no reply_stream frames");
        assertTrue(frames.stream().anyMatch(f -> "aibot_send_msg".equals(f.get("cmd"))),
                "answer must fall back to the proactive send path");
    }

    @Test
    @DisplayName("filter_tool_messages=false emits standalone tool trace messages")
    void toolTraceMessagesWhenUnfiltered() throws Exception {
        TestableAdapter adapter = newAdapter(
                "{\"progress_interval_ms\": 0, \"filter_tool_messages\": false}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "get_time", "arguments", "{\"tz\":\"cn\"}")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "get_time", "success", true)),
                new StreamDelta("好了", null));

        adapter.processStream(stream, inbound("alice"), "wecom:alice");

        List<String> markdowns = markdownContents(adapter.drainFrames());
        assertTrue(markdowns.stream().anyMatch(t -> t.contains("调用工具") && t.contains("get_time")),
                "expected a standalone tool-start trace, got: " + markdowns);
        assertTrue(markdowns.stream().anyMatch(t -> t.contains("get_time") && t.contains("完成")),
                "expected a standalone tool-completion trace, got: " + markdowns);
    }

    @Test
    @DisplayName("default filters emit no standalone tool messages")
    void noToolTraceByDefault() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.event("tool_call_started", Map.of("toolCallId", "c1", "toolName", "t1")),
                new StreamDelta("答案", null));

        adapter.processStream(stream, inbound("alice"), "wecom:alice");

        assertTrue(markdownContents(adapter.drainFrames()).stream().noneMatch(t -> t.contains("调用工具")),
                "default config must not leave standalone tool messages");
    }

    @Test
    @DisplayName("multi-segment reply: segments after the first ride the inbound frame, not proactive push")
    @SuppressWarnings("unchecked")
    void multiSegmentRidesInboundFrame() throws Exception {
        TestableAdapter adapter = newAdapter("{}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        // Long enough to exceed the 2048-char platform limit → at least 2 segments.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append("第").append(i)
                    .append("行：这是一段足够长的中文内容，用来撑破企业微信单条消息的长度上限，验证分段发送路径。\n");
        }
        adapter.renderAndSend("alice", sb.toString());

        List<Map<String, Object>> frames = adapter.drainFrames();
        assertTrue(frames.size() >= 2, "expected >= 2 outbound frames, got " + frames.size());

        // Segment 1 overwrites the stream bubble with finish=true.
        Map<String, Object> firstBody = (Map<String, Object>) frames.get(0).get("body");
        assertEquals("stream", firstBody.get("msgtype"), "first segment must close the stream bubble");

        // Segments 2+ must be markdown replies bound to the SAME inbound frame —
        // aibot_send_msg is rejected in group chats, so any proactive push here
        // would silently lose the segment for group users.
        for (int i = 1; i < frames.size(); i++) {
            Map<String, Object> frame = frames.get(i);
            assertEquals("aibot_respond_msg", frame.get("cmd"),
                    "segment #" + (i + 1) + " must ride the inbound frame reply slot");
            Map<String, Object> headers = (Map<String, Object>) frame.get("headers");
            assertEquals("req-1", headers.get("req_id"));
            Map<String, Object> body = (Map<String, Object>) frame.get("body");
            assertEquals("markdown", body.get("msgtype"));
        }
        assertTrue(frames.stream().noneMatch(f -> "aibot_send_msg".equals(f.get("cmd"))),
                "no segment may fall back to proactive push while a reply context exists");
    }

    // ==================== helpers ====================

    private static ChannelMessage inbound(String sender) {
        return ChannelMessage.builder()
                .channelType("wecom")
                .senderId(sender)
                .replyToken(sender)
                .content("hi")
                .build();
    }

    private static TestableAdapter newAdapter(String configJson) throws Exception {
        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("wecom");
        entity.setConfigJson(configJson);
        TestableAdapter adapter = new TestableAdapter(
                entity,
                Mockito.mock(ChannelMessageRouter.class),
                new ObjectMapper(),
                Mockito.mock(ApprovalNotificationService.class),
                Mockito.mock(WeComCardDispatcher.class),
                Mockito.mock(WeComKeepaliveScheduler.class));

        Field running = adapter.getClass().getSuperclass().getSuperclass()
                .getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(adapter)).set(true);
        // sendMessage / sendOutboundFrame gate on a live WebSocket reference;
        // sendFrame is overridden so the mock is never actually written to.
        Field ws = WeComChannelAdapter.class.getDeclaredField("webSocket");
        ws.setAccessible(true);
        ws.set(adapter, Mockito.mock(java.net.http.WebSocket.class));
        Method ensure = WeComChannelAdapter.class.getDeclaredMethod("ensureReplyExecutor");
        ensure.setAccessible(true);
        ensure.invoke(adapter);
        Method open = WeComChannelAdapter.class.getDeclaredMethod("openReplyQueue");
        open.setAccessible(true);
        open.invoke(adapter);
        adapter.workerIdleTimeoutMs = 60_000L;
        return adapter;
    }

    /** Insert a (frameReqId, processingStreamId) reply context for {@code replyToken}. */
    @SuppressWarnings("unchecked")
    private static void seedReplyContext(WeComChannelAdapter adapter, String replyToken,
                                         String reqId, String streamId) throws Exception {
        Field ctxField = WeComChannelAdapter.class.getDeclaredField("replyContexts");
        ctxField.setAccessible(true);
        Map<String, Object> contexts = (Map<String, Object>) ctxField.get(adapter);
        Class<?> ctxClass = Class.forName(
                "vip.mate.channel.wecom.WeComChannelAdapter$WeComReplyContext");
        Constructor<?> ctor = ctxClass.getDeclaredConstructor(String.class, String.class);
        ctor.setAccessible(true);
        contexts.put(replyToken, ctor.newInstance(reqId, streamId));
    }

    /** Extract every reply_stream body (in dispatch order) from the captured frames. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> streamBodies(List<Map<String, Object>> frames) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            Map<String, Object> body = (Map<String, Object>) frame.get("body");
            if (body != null && "stream".equals(body.get("msgtype"))) {
                out.add((Map<String, Object>) body.get("stream"));
            }
        }
        return out;
    }

    /** Extract every markdown message content from the captured frames. */
    @SuppressWarnings("unchecked")
    private static List<String> markdownContents(List<Map<String, Object>> frames) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            Map<String, Object> body = (Map<String, Object>) frame.get("body");
            if (body != null && "markdown".equals(body.get("msgtype"))) {
                Map<String, Object> md = (Map<String, Object>) body.get("markdown");
                if (md != null) {
                    out.add(String.valueOf(md.get("content")));
                }
            }
        }
        return out;
    }

    /** Frame-capturing adapter with auto-ACK, mirroring ReplyStreamDedupTest. */
    static class TestableAdapter extends WeComChannelAdapter {
        final LinkedBlockingQueue<Map<String, Object>> sentFrames = new LinkedBlockingQueue<>();
        private static final ExecutorService AUTOACK = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "test-autoack-progress");
            t.setDaemon(true);
            return t;
        });

        TestableAdapter(ChannelEntity entity, ChannelMessageRouter router,
                        ObjectMapper mapper, ApprovalNotificationService approvalSvc,
                        WeComCardDispatcher cardDispatcher, WeComKeepaliveScheduler keepalive) {
            super(entity, router, mapper, approvalSvc, cardDispatcher, keepalive);
        }

        /** Drain all frames dispatched so far, waiting briefly for the async worker. */
        List<Map<String, Object>> drainFrames() throws InterruptedException {
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, Object> frame;
            while ((frame = sentFrames.poll(500, TimeUnit.MILLISECONDS)) != null) {
                out.add(frame);
            }
            return out;
        }

        @Override
        @SuppressWarnings("unchecked")
        void sendFrame(Map<String, Object> frame) {
            sentFrames.offer(frame);
            Map<String, Object> headers = (Map<String, Object>) frame.get("headers");
            if (headers == null) return;
            String reqId = (String) headers.get("req_id");
            if (reqId == null || reqId.isBlank()) return;
            AUTOACK.submit(() -> completeAckSoon(reqId));
        }

        private void completeAckSoon(String reqId) {
            try {
                Thread.sleep(2);
                Field f = WeComChannelAdapter.class.getDeclaredField("pendingAcks");
                f.setAccessible(true);
                ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pending =
                        (ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>>) f.get(this);
                CompletableFuture<Map<String, Object>> fut = pending.get(reqId);
                if (fut != null) fut.complete(Map.of("errcode", 0));
            } catch (Exception ignored) {
            }
        }
    }
}
