package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.channel.web.ConversationInputQueueStore.QueuedInput;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.tool.document.preview.OfficePreviewService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerDurableQueueTest {

    @Test
    void interruptPersistsInputBeforePublishingAcceptance() {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ApprovalWorkflowService approvals = mock(ApprovalWorkflowService.class);
        ChatStreamTracker streams = mock(ChatStreamTracker.class);
        ConversationInputQueueStore queue = mock(ConversationInputQueueStore.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("alice");
        when(conversations.isConversationOwner("conv", "alice")).thenReturn(true);
        when(streams.isRunning("conv")).thenReturn(true);
        when(streams.notifyQueuedInput("conv")).thenReturn(true);
        QueuedInput stored = new QueuedInput(91L, "conv", 2L, "alice", "follow-up",
                List.of(), "queued", null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(queue.enqueue(eq("conv"), eq(2L), eq("alice"), eq("follow-up"),
                eq(List.of()), any())).thenReturn(stored);

        ChatController controller = new ChatController(agents, conversations, approvals, streams,
                new ObjectMapper(), mock(ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class), mock(ChatUploadLocationResolver.class),
                mock(OfficePreviewService.class), queue);
        ChatController.InterruptRequest request = new ChatController.InterruptRequest();
        request.setMessage("follow-up");
        request.setAgentId(2L);
        request.setContentParts(List.of());

        var response = controller.interruptStream("conv", request, authentication);

        assertThat(response.getData()).containsEntry("queued", true)
                .containsEntry("queueItemId", "91");
        var order = inOrder(queue, streams);
        order.verify(queue).enqueue(eq("conv"), eq(2L), eq("alice"),
                eq("follow-up"), eq(List.of()), any());
        order.verify(streams).notifyQueuedInput("conv");
    }
}
