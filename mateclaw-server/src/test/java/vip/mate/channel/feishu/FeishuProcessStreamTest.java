package vip.mate.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.agent.ContentKind;
import vip.mate.channel.ChannelMessage;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** End-to-end stream rendering at the Feishu adapter/CardKit boundary. */
class FeishuProcessStreamTest {

    private static final class RecordingManager extends FeishuStreamingCardManager {
        final List<String> snapshots = new CopyOnWriteArrayList<>();
        volatile boolean failCompletedSnapshots;
        volatile boolean failErrorSnapshots;
        volatile boolean failClose;

        RecordingManager(FeishuClientFactory factory, ObjectMapper mapper) {
            super(factory, mapper);
        }

        @Override protected String sdkCreateCard(Client client, String initialText) { return "card_1"; }
        @Override protected String sdkSendInteractiveMessage(Client client, String receiveIdType,
                                                              String receiveId, String cardId) { return "msg_1"; }
        @Override protected void sdkPushElementContent(Client client, String cardId, String elementId,
                                                        String content, int sequence) {
            if (failCompletedSnapshots && content.contains("✅ 已完成")) {
                throw new IllegalStateException("simulated final update failure");
            }
            if (failErrorSnapshots && content.contains("⚠️")) {
                throw new IllegalStateException("simulated error update failure");
            }
            snapshots.add(content);
        }
        @Override protected void sdkCloseStreamingMode(Client client, String cardId, int sequence,
                                                        String summary) {
            if (failClose) throw new IllegalStateException("simulated close failure");
        }
        @Override protected void sleepMillis(long millis) {}
    }

    private static final class RecordingAdapter extends FeishuChannelAdapter {
        final List<String> fallbackMessages = new CopyOnWriteArrayList<>();

        RecordingAdapter(ChannelEntity entity, ObjectMapper mapper, RecordingManager manager) {
            super(entity, mock(ChannelMessageRouter.class), mapper, null, null, manager);
        }

        @Override public void sendMessage(String targetId, String content) {
            fallbackMessages.add(content);
        }
    }

    @Test
    @DisplayName("default Feishu card shows a filtered execution trace and final answer")
    void defaultTraceShowsGenericToolsWithoutRawThinking() {
        Fixture f = fixture("{}");
        Flux<StreamDelta> stream = Flux.just(
                new StreamDelta(null, "内部推理文本"),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "get_time")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "get_time", "success", true)),
                new StreamDelta("现在是下午三点。", null));

        assertEquals("现在是下午三点。", f.adapter.processStream(stream, inbound(), "feishu:test"));

        String finalCard = f.manager.snapshots.get(f.manager.snapshots.size() - 1);
        assertTrue(finalCard.contains("执行轨迹"));
        assertTrue(finalCard.contains("已执行 1 项工具"));
        assertTrue(finalCard.contains("现在是下午三点。"));
        assertFalse(finalCard.contains("get_time"), "default tool filter must hide tool identity");
        assertFalse(finalCard.contains("内部推理文本"), "default thinking filter must hide raw thinking");
    }

    @Test
    @DisplayName("Feishu card honors unfiltered thinking and tool-detail settings")
    void unfilteredTraceShowsThinkingAndToolName() {
        Fixture f = fixture("{\"filter_thinking\":false,\"filter_tool_messages\":false}");
        Flux<StreamDelta> stream = Flux.just(
                new StreamDelta(null, "先读取当前时间"),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "get_time")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "get_time", "success", true)),
                new StreamDelta("完成。", null));

        f.adapter.processStream(stream, inbound(), "feishu:test");

        String finalCard = f.manager.snapshots.get(f.manager.snapshots.size() - 1);
        assertTrue(finalCard.contains("先读取当前时间"));
        assertTrue(finalCard.contains("get_time"));
        assertTrue(finalCard.contains("完成。"));
    }

    @Test
    @DisplayName("pre-tool rehearsal remains live but is removed from the completed Feishu card")
    void provisionalNarrationDoesNotBecomePermanent() {
        Fixture f = fixture("{}");
        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.segmentOnly("预测温度是 29 度。", null, ContentKind.PRE_TOOL_NARRATION),
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "query_env")),
                StreamDelta.event("tool_call_completed",
                        Map.of("toolCallId", "c1", "toolName", "query_env", "success", true)),
                new StreamDelta("接口没有返回环境数据。", null));

        f.adapter.processStream(stream, inbound(), "feishu:test");

        assertTrue(f.manager.snapshots.stream().anyMatch(s -> s.contains("预测温度是 29 度")),
                "provisional narration should be visible while work is in progress");
        String finalCard = f.manager.snapshots.get(f.manager.snapshots.size() - 1);
        assertFalse(finalCard.contains("29 度"), "superseded rehearsal must not survive completion");
        assertTrue(finalCard.contains("接口没有返回环境数据"));
    }

    @Test
    @DisplayName("execution trace is presentation-only and an empty turn stays empty for persistence")
    void emptyTurnDoesNotPersistTrace() {
        Fixture f = fixture("{}");
        Flux<StreamDelta> stream = Flux.just(
                StreamDelta.event("tool_call_started",
                        Map.of("toolCallId", "c1", "toolName", "approval_tool")),
                StreamDelta.event("tool_approval_requested", Map.of("toolCallId", "c1")));

        assertEquals("", f.adapter.processStream(stream, inbound(), "feishu:test"),
                "the router must never persist the rendered execution trace as assistant content");
        String finalCard = f.manager.snapshots.get(f.manager.snapshots.size() - 1);
        assertTrue(finalCard.contains("等待工具审批"));
    }

    @Test
    @DisplayName("failed terminal CardKit update falls back to a regular Feishu message")
    void failedFinalCardUpdateFallsBackToRegularMessage() {
        Fixture f = fixture("{}");
        f.manager.failCompletedSnapshots = true;

        assertEquals("最终答案", f.adapter.processStream(
                Flux.just(new StreamDelta("最终答案", null)), inbound(), "feishu:test"));

        assertEquals(1, f.adapter.fallbackMessages.size());
        assertTrue(f.adapter.fallbackMessages.get(0).contains("最终答案"));
    }

    @Test
    @DisplayName("failed streaming close also falls back after retry")
    void failedStreamingCloseFallsBackToRegularMessage() {
        Fixture f = fixture("{}");
        f.manager.failClose = true;

        f.adapter.processStream(Flux.just(new StreamDelta("最终答案", null)),
                inbound(), "feishu:test");

        assertEquals(1, f.adapter.fallbackMessages.size());
        assertTrue(f.adapter.fallbackMessages.get(0).contains("最终答案"));
    }

    @Test
    @DisplayName("failed error-card update also sends a regular error fallback")
    void failedErrorCardUpdateFallsBackToRegularMessage() {
        Fixture f = fixture("{}");
        f.manager.failErrorSnapshots = true;
        Flux<StreamDelta> stream = Flux.concat(
                Flux.just(new StreamDelta("部分回答", null)),
                Flux.error(new IllegalStateException("upstream failed")));

        String result = f.adapter.processStream(stream, inbound(), "feishu:test");

        assertTrue(result.startsWith("[错误]"));
        assertEquals(1, f.adapter.fallbackMessages.size());
        assertTrue(f.adapter.fallbackMessages.get(0).contains("upstream failed"));
    }

    private static ChannelMessage inbound() {
        return ChannelMessage.builder()
                .channelType("feishu")
                .senderId("ou_user")
                .replyToken("oc_chat")
                .content("hi")
                .build();
    }

    private static Fixture fixture(String configJson) {
        ObjectMapper mapper = new ObjectMapper();
        FeishuClientFactory factory = mock(FeishuClientFactory.class);
        when(factory.client(anyLong())).thenReturn(mock(Client.class));
        when(factory.client(any())).thenReturn(mock(Client.class));
        RecordingManager manager = new RecordingManager(factory, mapper);

        ChannelEntity entity = new ChannelEntity();
        entity.setId(1L);
        entity.setChannelType("feishu");
        entity.setConfigJson(configJson);
        RecordingAdapter adapter = new RecordingAdapter(entity, mapper, manager);
        return new Fixture(adapter, manager);
    }

    private record Fixture(RecordingAdapter adapter, RecordingManager manager) {}
}
