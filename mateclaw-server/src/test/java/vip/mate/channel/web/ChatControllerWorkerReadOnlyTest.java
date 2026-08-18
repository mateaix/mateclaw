package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import vip.mate.agent.AgentService;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.tool.document.preview.OfficePreviewService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatControllerWorkerReadOnlyTest {

    @Mock private AgentService agentService;
    @Mock private ConversationService conversationService;
    @Mock private ApprovalWorkflowService approvalService;
    @Mock private ChatStreamTracker streamTracker;
    @Mock private ObjectMapper objectMapper;
    @Mock private ConversationCompletionPublisher completionPublisher;
    @Mock private MemoryOwnerResolver memoryOwnerResolver;
    @Mock private ChatUploadLocationResolver uploadLocationResolver;
    @Mock private OfficePreviewService officePreviewService;
    @Mock private Authentication authentication;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(agentService, conversationService, approvalService,
                streamTracker, objectMapper, completionPublisher, memoryOwnerResolver,
                uploadLocationResolver, officePreviewService);
    }

    @Test
    void rejectsWorkerBeforeRegisteringOrStartingAUserStream() {
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("worker-conversation");
        request.setMessage("try to continue");
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("worker-conversation")).thenReturn(false);

        controller.chatStream(request, 1L, authentication);

        verify(conversationService).isUserMessageAllowed("worker-conversation");
        verify(streamTracker, never()).register(any());
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsLegacyWorkerBeforeRegisteringOrStartingAUserStream() {
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("team-task-legacy");
        request.setMessage("try to continue");
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("team-task-legacy")).thenReturn(false);

        controller.chatStream(request, 1L, authentication);

        verify(conversationService).isUserMessageAllowed("team-task-legacy");
        verify(streamTracker, never()).register(any());
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }
}
