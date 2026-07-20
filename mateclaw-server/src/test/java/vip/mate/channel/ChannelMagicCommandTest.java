package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChannelMagicCommandTest {

    @Test
    @DisplayName("clear aliases are recognized as channel magic commands")
    void clearAliasesAreRecognized() {
        assertTrue(ChannelMagicCommand.isClearCommand("clear"));
        assertTrue(ChannelMagicCommand.isClearCommand("/clear"));
        assertTrue(ChannelMagicCommand.isClearCommand("  清空上下文  "));
        assertTrue(ChannelMagicCommand.isClearCommand("清理上下文"));
        assertTrue(ChannelMagicCommand.isClearCommand("/reset"));

        assertFalse(ChannelMagicCommand.isClearCommand("clear 一下北京天气"));
        assertFalse(ChannelMagicCommand.isClearCommand("请清理上下文后继续"));
        assertFalse(ChannelMagicCommand.isClearCommand("/approval approve abc123"));
    }

    @Test
    @DisplayName("clear command wipes current channel conversation and does not call agent")
    void clearCommandClearsConversationWithoutCallingAgent() throws Exception {
        AgentService agentService = mock(AgentService.class);
        ConversationService conversationService = mock(ConversationService.class);
        ChannelService channelService = mock(ChannelService.class);
        ChannelSessionStore channelSessionStore = mock(ChannelSessionStore.class);
        ApprovalWorkflowService approvalService = mock(ApprovalWorkflowService.class);
        ApprovalNotificationService approvalNotificationService = mock(ApprovalNotificationService.class);
        ConversationCompletionPublisher completionPublisher = mock(ConversationCompletionPublisher.class);
        TtsService ttsService = mock(TtsService.class);
        ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
        ChannelChatOriginFactory chatOriginFactory = mock(ChannelChatOriginFactory.class);
        ChannelErrorClassifier errorClassifier = mock(ChannelErrorClassifier.class);
        ChannelMessageRouter router = new ChannelMessageRouter(agentService, conversationService,
                channelService, channelSessionStore, approvalService, approvalNotificationService,
                completionPublisher, ttsService, new ObjectMapper(), streamTracker,
                chatOriginFactory, errorClassifier);

        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.getChannelType()).thenReturn("wecom");
        ChannelEntity channel = new ChannelEntity();
        channel.setAgentId(100L);
        ChannelMessage message = ChannelMessage.builder()
                .senderId("alice")
                .replyToken("reply-1")
                .content("/clear")
                .build();

        Method process = ChannelMessageRouter.class.getDeclaredMethod(
                "processMessage", ChannelMessage.class, ChannelAdapter.class, ChannelEntity.class, String.class);
        process.setAccessible(true);
        process.invoke(router, message, adapter, channel, "wecom:alice");

        verify(conversationService).clearMessages("wecom:alice");
        verify(adapter).sendMessage(eq("reply-1"), contains("上下文已清理"));
        verify(conversationService, never()).saveMessage(anyString(), anyString(), anyString(), any(), anyString());
        verify(agentService, never()).chatStructuredStream(
                anyLong(), anyString(), anyString(), anyString(), any(ChatOrigin.class));
    }
}
