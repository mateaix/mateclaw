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
        TestableAdapter adapter = newAdapter(
                "{\"progress_interval_ms\": 0, \"filter_tool_messages\": false}");
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
    @DisplayName("filter_tool_messages=true keeps the tool trail out of the progress bubble too")
    void progressBubbleHonorsToolFilter() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "execute_code")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "execute_code", "success", true)),
                new StreamDelta("好了。", null));

        assertEquals("好了。", adapter.processStream(stream, inbound("alice"), "wecom:alice"));

        List<Map<String, Object>> streamBodies = streamBodies(adapter.drainFrames());
        assertFalse(streamBodies.isEmpty(), "expected reply_stream progress frames");
        for (Map<String, Object> body : streamBodies) {
            String content = String.valueOf(body.get("content"));
            assertFalse(content.contains("execute_code"),
                    "filtered run must never name the tool: " + content);
            assertFalse(content.contains("✅ execute_code"), content);
        }
        boolean sawGenericToolStatus = streamBodies.stream()
                .anyMatch(s -> String.valueOf(s.get("content")).contains("正在执行工具"));
        assertTrue(sawGenericToolStatus, "still tells the user a tool is running, just not which");
    }

    @Test
    @DisplayName("a closing narration equal to the final answer is dropped, not shown twice")
    void narrationEqualToAnswerIsNotDuplicated() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        String answer = "中控测试会议室当前无人使用，电池还剩 1%。";
        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.segmentOnly(answer, null),
                new StreamDelta(answer, null));

        assertEquals(answer, adapter.processStream(stream, inbound("alice"), "wecom:alice"));

        List<Map<String, Object>> finished = streamBodies(adapter.drainFrames()).stream()
                .filter(s -> Boolean.TRUE.equals(s.get("finish"))).toList();
        assertEquals(1, finished.size(),
                "narration restating the answer must not close a bubble of its own");
        assertTrue(String.valueOf(finished.get(0).get("content")).contains("无人使用"));
    }

    @Test
    @DisplayName("a turn with no answer closes the progress bubble instead of stranding 思考中")
    void emptyAnswerClosesProgressBubble() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "execute_code")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "execute_code", "success", true)));

        assertEquals("", adapter.processStream(stream, inbound("alice"), "wecom:alice"));

        List<Map<String, Object>> streamBodies = streamBodies(adapter.drainFrames());
        assertFalse(streamBodies.isEmpty(), "expected reply_stream frames");
        Map<String, Object> last = streamBodies.get(streamBodies.size() - 1);
        assertEquals(Boolean.TRUE, last.get("finish"),
                "the bubble must be closed, otherwise it sits at 思考中 forever");
        assertFalse(String.valueOf(last.get("content")).contains("思考中"), String.valueOf(last.get("content")));
    }

    @Test
    @DisplayName("degraded path keeps stage narration out of the reply text")
    void degradedPathExcludesNarration() throws Exception {
        TestableAdapter adapter = newAdapter("{\"stream_progress\": false}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.segmentOnly("我先查一下：", null),
                new StreamDelta("答案", null));

        assertEquals("答案", adapter.processStream(stream, inbound("alice"), "wecom:alice"),
                "narration glued into the reply is what makes the answer read as sent twice");
    }

    @Test
    @DisplayName("post-tool narrations roll the bubble; the pre-tool opener is dropped, final answer excludes both")
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
        // Two finished bubbles: the observation-grounded narration #2 and the
        // final answer. Narration #1 ran before any tool observation and tools
        // ran after it — a pre-tool rehearsal never becomes a permanent bubble
        // (it stays visible only transiently in the live progress snapshot).
        assertEquals(2, finished.size(),
                "grounded narration + final answer close one bubble each; the rehearsal closes none");
        assertTrue(finished.stream().noneMatch(
                        s -> String.valueOf(s.get("content")).contains("我先查一下当前时间")),
                "the pre-tool rehearsal must not finalize a bubble of its own");
        assertTrue(String.valueOf(finished.get(0).get("content")).contains("再查会议室"));
        assertTrue(String.valueOf(finished.get(1).get("content")).contains("已预约"));
        assertEquals(2, finished.stream().map(s -> s.get("id")).distinct().count(),
                "each finished bubble must ride its own stream id");
        // The first published narration finalizes the original placeholder stream.
        assertEquals("stream-1", finished.get(0).get("id"));
    }

    @Test
    @DisplayName("a pre-tool rehearsal pending at stream end is dropped once a grounded answer exists")
    void preToolRehearsalDroppedForGroundedAnswer() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        // The model writes a full predicted result (fabricated numbers) before
        // its first tool call; the real observation then produces the answer.
        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.segmentOnly("环境监测结果：温度 29.0°C，湿度 63.0%。需要进一步操作吗？", null),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "query_env")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "query_env", "success", true)),
                new StreamDelta("接口返回为空，所有会议室均无环境数据。", null));

        String result = adapter.processStream(stream, inbound("alice"), "wecom:alice");

        assertEquals("接口返回为空，所有会议室均无环境数据。", result);
        List<Map<String, Object>> finished = streamBodies(adapter.drainFrames()).stream()
                .filter(s -> Boolean.TRUE.equals(s.get("finish"))).toList();
        assertEquals(1, finished.size(), "only the grounded answer may close a bubble");
        String content = String.valueOf(finished.get(0).get("content"));
        assertTrue(content.contains("接口返回为空"));
        assertFalse(content.contains("29.0"), "the fabricated rehearsal must never reach the user: " + content);
    }

    @Test
    @DisplayName("a pre-tool narration is still published when the turn produced no answer at all")
    void preToolNarrationKeptWhenNoAnswer() throws Exception {
        TestableAdapter adapter = newAdapter("{\"progress_interval_ms\": 0}");
        seedReplyContext(adapter, "alice", "req-1", "stream-1");

        // No content after the tool ran — the narration is everything the user
        // gets (approval park / stop / empty answer). With no replacement
        // content it is not superseded, so it must still close the bubble.
        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.segmentOnly("我先调用工具查询状态：", null),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "query_env")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "query_env", "success", true)));

        assertEquals("", adapter.processStream(stream, inbound("alice"), "wecom:alice"));

        List<Map<String, Object>> finished = streamBodies(adapter.drainFrames()).stream()
                .filter(s -> Boolean.TRUE.equals(s.get("finish"))).toList();
        assertEquals(1, finished.size(), "the narration must close the bubble when nothing else can");
        assertTrue(String.valueOf(finished.get(0).get("content")).contains("我先调用工具查询状态"));
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
