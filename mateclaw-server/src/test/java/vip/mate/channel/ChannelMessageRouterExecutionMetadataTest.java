package vip.mate.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentService.StreamDelta;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.notification.ApprovalNotificationService;
import vip.mate.channel.service.ChannelService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.tts.TtsService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IM-routed turns must persist the same execution record (metadata.segments /
 * metadata.toolCalls / content parts / token usage) that Web direct chats do,
 * so the Web console renders tool calls and request/response payloads for
 * conversations driven from WeCom, DingTalk, Telegram, etc.
 *
 * <p>Also pins the live-mirror contract: tool events reach the stream tracker
 * for any Web observer, while accumulator-internal events ({@code _usage_final})
 * are consumed internally and never leak to subscribers.
 */
class ChannelMessageRouterExecutionMetadataTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("sync path persists toolCalls + segments metadata, parts, and token usage")
    void syncPathPersistsExecutionMetadata() throws Exception {
        Fixture f = new Fixture();
        f.streamReturns(
                StreamDelta.event("tool_call_started", Map.of(
                        "toolCallId", "tc-1",
                        "toolName", "meeting_room_query",
                        "arguments", "{\"room\":\"1号\"}")),
                StreamDelta.event("tool_call_completed", Map.of(
                        "toolCallId", "tc-1",
                        "toolName", "meeting_room_query",
                        "result", "17:00-17:30 空闲",
                        "success", true)),
                new StreamDelta("1号会议室该时段空闲,可以预约。", null),
                StreamDelta.event("_usage_final", Map.of(
                        "promptTokens", 100,
                        "completionTokens", 20,
                        "cacheReadTokens", 5,
                        "cacheWriteTokens", 3,
                        "reasoningTokens", 2,
                        "runtimeModelName", "deepseek-v4-pro",
                        "runtimeProviderId", "deepseek")));

        f.process("1号会议室下午5点空闲吗");

        ArgumentCaptor<List<MessageContentPart>> partsCaptor = ArgumentCaptor.captor();
        ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.captor();
        verify(f.conversationService).saveMessage(
                eq("telegram:alice"), eq("assistant"), eq("1号会议室该时段空闲,可以预约。"),
                partsCaptor.capture(), eq("completed"),
                eq(100), eq(20), eq(5), eq(3), eq(2),
                eq("deepseek-v4-pro"), eq("deepseek"),
                metadataCaptor.capture());

        // Parts: text part with the reply + a tool_call part carrying the payload
        List<MessageContentPart> parts = partsCaptor.getValue();
        assertTrue(parts.stream().anyMatch(p -> "text".equals(p.getType())),
                "expected a text part");
        assertTrue(parts.stream().anyMatch(p -> "tool_call".equals(p.getType())),
                "expected a tool_call part");

        // Metadata: toolCalls flat view with args + result, segments timeline
        JsonNode metadata = json.readTree(metadataCaptor.getValue());
        JsonNode toolCalls = metadata.get("toolCalls");
        assertNotNull(toolCalls, "metadata.toolCalls must be persisted");
        assertEquals(1, toolCalls.size());
        assertEquals("meeting_room_query", toolCalls.get(0).get("name").asText());
        assertEquals("{\"room\":\"1号\"}", toolCalls.get(0).get("arguments").asText());
        assertEquals("17:00-17:30 空闲", toolCalls.get(0).get("result").asText());
        assertEquals("completed", toolCalls.get(0).get("status").asText());

        JsonNode segments = metadata.get("segments");
        assertNotNull(segments, "metadata.segments must be persisted");
        boolean hasToolSegment = false;
        boolean hasContentSegment = false;
        for (JsonNode seg : segments) {
            if ("tool_call".equals(seg.get("type").asText())) hasToolSegment = true;
            if ("content".equals(seg.get("type").asText())) hasContentSegment = true;
        }
        assertTrue(hasToolSegment, "segments timeline must contain the tool_call entry");
        assertTrue(hasContentSegment, "segments timeline must contain the content entry");
    }

    @Test
    @DisplayName("tool events are mirrored live to the stream tracker; _usage_final never leaks")
    void toolEventsMirroredInternalEventsConsumed() throws Exception {
        Fixture f = new Fixture();
        f.streamReturns(
                StreamDelta.event("tool_call_started", Map.of(
                        "toolCallId", "tc-1", "toolName", "web_search", "arguments", "{}")),
                StreamDelta.event("tool_call_completed", Map.of(
                        "toolCallId", "tc-1", "toolName", "web_search",
                        "result", "ok", "success", true)),
                new StreamDelta("done", null),
                StreamDelta.event("_usage_final", Map.of("promptTokens", 1)));

        f.process("查一下");

        verify(f.streamTracker).broadcastObject(eq("telegram:alice"), eq("tool_call_started"), any());
        verify(f.streamTracker).broadcastObject(eq("telegram:alice"), eq("tool_call_completed"), any());
        verify(f.streamTracker).broadcastObject(eq("telegram:alice"), eq("content_delta"), any());
        verify(f.streamTracker, never()).broadcastObject(anyString(), eq("_usage_final"), any());
    }

    // ==================== helpers ====================

    /** Mocks + router wiring shared by the sync-path tests. */
    private static final class Fixture {
        final AgentService agentService = mock(AgentService.class);
        final ConversationService conversationService = mock(ConversationService.class);
        final ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
        final ChannelAdapter adapter = mock(ChannelAdapter.class);
        final ApprovalWorkflowService approvalService = mock(ApprovalWorkflowService.class);
        final ChannelErrorClassifier errorClassifier = mock(ChannelErrorClassifier.class);
        final ChannelChatOriginFactory chatOriginFactory = mock(ChannelChatOriginFactory.class);
        final ChannelEntity channel = new ChannelEntity();
        final ChannelMessageRouter router;

        Fixture() {
            ChannelService channelService = mock(ChannelService.class);
            ChannelSessionStore channelSessionStore = mock(ChannelSessionStore.class);
            ApprovalNotificationService approvalNotificationService = mock(ApprovalNotificationService.class);
            ConversationCompletionPublisher completionPublisher = mock(ConversationCompletionPublisher.class);
            TtsService ttsService = mock(TtsService.class);
            router = new ChannelMessageRouter(agentService, conversationService,
                    channelService, channelSessionStore, approvalService, approvalNotificationService,
                    completionPublisher, ttsService, new ObjectMapper(), streamTracker,
                    chatOriginFactory, errorClassifier);
            when(adapter.getChannelType()).thenReturn("telegram");
            when(chatOriginFactory.from(any(), any(), any(), any())).thenReturn(ChatOrigin.EMPTY);
            channel.setAgentId(100L);
        }

        void streamReturns(StreamDelta... deltas) {
            when(agentService.chatStructuredStream(
                    eq(100L), anyString(), anyString(), anyString(), any(ChatOrigin.class)))
                    .thenReturn(Flux.fromArray(deltas));
        }

        void process(String content) throws Exception {
            ChannelMessage message = ChannelMessage.builder()
                    .senderId("alice")
                    .replyToken("reply-1")
                    .content(content)
                    .build();
            Method process = ChannelMessageRouter.class.getDeclaredMethod(
                    "processMessage", ChannelMessage.class, ChannelAdapter.class,
                    ChannelEntity.class, String.class);
            process.setAccessible(true);
            process.invoke(router, message, adapter, channel, "telegram:alice");
        }
    }
}
