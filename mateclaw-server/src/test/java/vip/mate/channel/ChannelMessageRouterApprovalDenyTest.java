package vip.mate.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.PendingApproval;
import vip.mate.approval.ResolveOutcome;
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

class ChannelMessageRouterApprovalDenyTest {

    @Test
    void denyAlreadyResolvedDoesNotRewriteConversationOrBroadcastDeniedHint() throws Exception {
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

        PendingApproval pending = new PendingApproval("abcdef123", "conv-1", "alice",
                "dangerous_tool", "{}", "needs approval");
        when(approvalService.findPendingByConversation("conv-1")).thenReturn(pending);
        when(approvalService.resolve("abcdef123", "alice", "denied"))
                .thenReturn(ResolveOutcome.alreadyResolved("abcdef123"));
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapter.getChannelType()).thenReturn("test");
        ChannelEntity channel = new ChannelEntity();
        channel.setAgentId(100L);
        ChannelMessage message = ChannelMessage.builder()
                .senderId("alice")
                .replyToken("reply-1")
                .content("/deny abcdef")
                .build();

        Method process = ChannelMessageRouter.class.getDeclaredMethod(
                "processMessage", ChannelMessage.class, ChannelAdapter.class, ChannelEntity.class, String.class);
        process.setAccessible(true);
        process.invoke(router, message, adapter, channel, "conv-1");

        verify(conversationService, never()).removeApprovalPlaceholders(anyString());
        verify(conversationService, never()).saveMessage(anyString(), anyString(), anyString(), any(), anyString());
        verify(adapter).sendMessage("reply-1", "⚠️ 审批记录已过期或已被处理。");
        verify(adapter, never()).sendMessage(eq("reply-1"), startsWith("⛔ 已拒绝执行工具"));
    }
}
