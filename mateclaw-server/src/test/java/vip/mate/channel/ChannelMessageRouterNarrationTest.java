package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sync-path narration routing for non-streaming IM adapters.
 *
 * <p>Per-iteration narration deltas ({@code segmentOnly}) must be relayed as
 * standalone messages the moment they arrive and stay out of the accumulated
 * final reply — otherwise multiple iterations glue into a wall of text and
 * the persisted assistant message pollutes the next turn's LLM history with
 * unanswered stated intents (issue #120).
 */
class ChannelMessageRouterNarrationTest {

    @Test
    @DisplayName("segmentOnly narration is sent immediately and excluded from the final reply")
    void narrationRelayedImmediatelyAndExcludedFromReply() throws Exception {
        Fixture f = new Fixture();
        f.streamReturns(
                StreamDelta.segmentOnly("我先查一下天气", null),
                StreamDelta.segmentOnly("再帮你汇总结果", null),
                StreamDelta.persistOnly("今天北京晴,26 度。", null));

        f.process("帮我查天气");

        InOrder inOrder = inOrder(f.adapter);
        inOrder.verify(f.adapter).renderAndSend("reply-1", "我先查一下天气");
        inOrder.verify(f.adapter).renderAndSend("reply-1", "再帮你汇总结果");
        inOrder.verify(f.adapter).renderAndSend("reply-1", "今天北京晴,26 度。");
        f.verifyPersistedAssistantContent("今天北京晴,26 度。");
        f.verifyNoProcessingError();
    }

    @Test
    @DisplayName("blank narration deltas are skipped entirely")
    void blankNarrationSkipped() throws Exception {
        Fixture f = new Fixture();
        f.streamReturns(
                StreamDelta.segmentOnly("   ", null),
                StreamDelta.persistOnly("最终答案", null));

        f.process("你好");

        verify(f.adapter, times(1)).renderAndSend(anyString(), anyString());
        verify(f.adapter).renderAndSend("reply-1", "最终答案");
        f.verifyPersistedAssistantContent("最终答案");
    }

    @Test
    @DisplayName("stream_progress=false suppresses narration relay but never re-adds it to the reply")
    void narrationSuppressedWhenProgressDisabled() throws Exception {
        Fixture f = new Fixture();
        f.channel.setConfigJson("{\"stream_progress\": false}");
        f.streamReturns(
                StreamDelta.segmentOnly("我先查一下天气", null),
                StreamDelta.persistOnly("今天晴。", null));

        f.process("帮我查天气");

        verify(f.adapter, never()).renderAndSend("reply-1", "我先查一下天气");
        verify(f.adapter).renderAndSend("reply-1", "今天晴。");
        f.verifyPersistedAssistantContent("今天晴。");
    }

    @Test
    @DisplayName("a failed narration send does not abort the run — final reply still goes out")
    void narrationSendFailureIsNonFatal() throws Exception {
        Fixture f = new Fixture();
        doThrow(new RuntimeException("boom"))
                .when(f.adapter).renderAndSend("reply-1", "我先查一下天气");
        f.streamReturns(
                StreamDelta.segmentOnly("我先查一下天气", null),
                StreamDelta.persistOnly("今天晴。", null));

        f.process("帮我查天气");

        verify(f.adapter).renderAndSend("reply-1", "今天晴。");
        f.verifyPersistedAssistantContent("今天晴。");
        f.verifyNoProcessingError();
    }

    @Test
    @DisplayName("persistOnly and plain content deltas still accumulate into one reply")
    void nonNarrationDeltasStillAccumulate() throws Exception {
        Fixture f = new Fixture();
        f.streamReturns(
                new StreamDelta("答案第一段。", null),
                StreamDelta.persistOnly("答案第二段。", null));

        f.process("你好");

        verify(f.adapter).renderAndSend("reply-1", "答案第一段。答案第二段。");
        f.verifyPersistedAssistantContent("答案第一段。答案第二段。");
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

        /** The persisted assistant row must hold the final-answer span only. */
        void verifyPersistedAssistantContent(String expected) {
            verify(conversationService).saveMessage(
                    eq("telegram:alice"), eq("assistant"), eq(expected), isNull(), eq("completed"),
                    eq(0), eq(0), eq(0), eq(0), eq(0), isNull(), isNull(), isNull());
        }

        /** processMessage swallows failures into a generic error reply — assert none fired. */
        void verifyNoProcessingError() {
            verify(adapter, never()).sendMessage(anyString(), contains("处理消息时出现错误"));
        }
    }
}
