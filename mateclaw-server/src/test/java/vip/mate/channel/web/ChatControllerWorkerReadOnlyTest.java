package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.ConversationTurnGate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;

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
    @Mock private ConversationInputQueueStore inputQueue;
    @Mock private Authentication authentication;

    private ChatController controller;
    private final ConversationTurnGate gate = new ConversationTurnGate();

    @BeforeEach
    void setUp() {
        controller = new ChatController(agentService, conversationService, approvalService,
                streamTracker, objectMapper, completionPublisher, memoryOwnerResolver,
                uploadLocationResolver, officePreviewService, inputQueue);
        ReflectionTestUtils.setField(controller, "turnGate", gate);
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

    @Test
    void rejectsApprovalWhileAnotherProducerOwnsTheStream() {
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("busy-conversation");
        request.setMessage("/approve");
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("busy-conversation")).thenReturn(true);
        org.mockito.Mockito.lenient().when(streamTracker.isRunning("busy-conversation")).thenReturn(true);

        controller.chatStream(request, 1L, authentication);

        verify(approvalService, never()).findPendingByConversation(any());
        verify(approvalService, never()).resolveAndConsume(any(), any());
        verify(streamTracker, never()).register(any());
        verify(conversationService, never()).removeApprovalPlaceholders(any());
    }

    @Test
    void rejectsForeignConversationBeforeInspectingApproval() {
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("foreign-conversation");
        request.setMessage("/approve");
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("foreign-conversation")).thenReturn(true);
        org.mockito.Mockito.lenient().when(conversationService.conversationExists("foreign-conversation")).thenReturn(true);

        controller.chatStream(request, 1L, authentication);

        verify(approvalService, never()).findPendingByConversation(any());
        verify(streamTracker, never()).register(any());
    }

    @Test
    void synchronousChatRejectsBusyStreamBeforePersistingInput() {
        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setConversationId("busy-conversation");
        request.setMessage("new request");
        when(authentication.getName()).thenReturn("alice");
        org.mockito.Mockito.lenient().when(streamTracker.isRunning("busy-conversation")).thenReturn(true);
        org.mockito.Mockito.lenient().when(agentService.chatWithUsage(any(), any(), any(), any()))
                .thenReturn(new AgentService.ChatResult("reply", 0, 0, "model", "provider"));

        controller.chat(1L, request, 1L, authentication);

        verify(conversationService, never()).getOrCreateConversation(any(), any(), any(), any());
        verify(conversationService, never()).saveMessage(any(), any(), any(), org.mockito.ArgumentMatchers.anyList());
        verify(agentService, never()).chatWithUsage(any(), any(), any(), any());
    }

    @Test
    void autonomousReservationRejectsApprovalAndRegenerationBeforeMutation() {
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("auto-conversation")).thenReturn(true);
        try (var autonomous = gate.tryAcquire("auto-conversation")) {
            assertNotNull(autonomous);
            ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
            request.setConversationId("auto-conversation");
            request.setMessage("/approve");
            controller.chatStream(request, 1L, authentication);
            request.setMessage("continue");
            request.setRegenerate(true);
            controller.chatStream(request, 1L, authentication);

            assertNull(gate.tryAcquire("auto-conversation"), "rejected requests must not release another owner");
        }

        verify(approvalService, never()).findPendingByConversation(any());
        verify(approvalService, never()).resolveAndConsume(any(), any());
        verify(conversationService, never()).prepareRegenerate(any());
        verify(streamTracker, never()).register(any());
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingApprovalReleasesSetupReservation() {
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("idle-conversation")).thenReturn(true);
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("idle-conversation");
        request.setMessage("/approve");

        controller.chatStream(request, 1L, authentication);

        verify(approvalService).findPendingByConversation("idle-conversation");
        try (var next = gate.tryAcquire("idle-conversation")) {
            assertNotNull(next);
        }
    }

    @Test
    void idleApprovalIsConsumedWhileHoldingSetupReservation() {
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("idle-conversation")).thenReturn(true);
        var pending = org.mockito.Mockito.mock(vip.mate.approval.PendingApproval.class);
        when(pending.getPendingId()).thenReturn("pending-1");
        when(approvalService.findPendingByConversation("idle-conversation")).thenReturn(pending);
        when(approvalService.resolveAndConsume("pending-1", "alice")).thenAnswer(invocation -> {
            assertNull(gate.tryAcquire("idle-conversation"), "approval consumption must reserve ingress");
            return vip.mate.approval.ResolveOutcome.alreadyResolved("pending-1");
        });
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("idle-conversation");
        request.setMessage("/approve");

        controller.chatStream(request, 1L, authentication);

        verify(approvalService).resolveAndConsume("pending-1", "alice");
        try (var next = gate.tryAcquire("idle-conversation")) {
            assertNotNull(next);
        }
    }

    @Test
    void approvalCommandConsumesTheRequestedPendingInsteadOfTheOldest() {
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("multi-approval")).thenReturn(true);
        var requested = org.mockito.Mockito.mock(vip.mate.approval.PendingApproval.class);
        when(requested.getPendingId()).thenReturn("pending-newest");
        when(requested.getConversationId()).thenReturn("multi-approval");
        when(requested.getStatus()).thenReturn("pending");
        when(approvalService.getPending("pending-newest")).thenReturn(Optional.of(requested));
        when(approvalService.resolveAndConsume("pending-newest", "alice"))
                .thenReturn(vip.mate.approval.ResolveOutcome.alreadyResolved("pending-newest"));

        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("multi-approval");
        request.setMessage("/approve");
        request.setPendingApprovalId("pending-newest");

        controller.chatStream(request, 1L, authentication);

        verify(approvalService).getPending("pending-newest");
        verify(approvalService).resolveAndConsume("pending-newest", "alice");
        verify(approvalService, never()).findPendingByConversation(any());
    }

    @Test
    void approvalCommandRejectsPendingFromAnotherConversation() {
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isUserMessageAllowed("owned-conversation")).thenReturn(true);
        var foreign = org.mockito.Mockito.mock(vip.mate.approval.PendingApproval.class);
        when(foreign.getConversationId()).thenReturn("foreign-conversation");
        when(approvalService.getPending("foreign-pending")).thenReturn(Optional.of(foreign));

        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("owned-conversation");
        request.setMessage("/deny");
        request.setPendingApprovalId("foreign-pending");

        controller.chatStream(request, 1L, authentication);

        verify(approvalService, never()).resolve(any(), any(), any());
        verify(approvalService, never()).findPendingByConversation(any());
    }

    @Test
    void reconnectCanAttachWhileAutonomousTurnOwnsReservation() {
        when(authentication.getName()).thenReturn("alice");
        when(conversationService.isConversationOwner("auto-conversation", "alice")).thenReturn(true);
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setConversationId("auto-conversation");
        request.setReconnect(true);
        try (var autonomous = gate.tryAcquire("auto-conversation")) {
            assertNotNull(autonomous);
            controller.chatStream(request, 1L, authentication);
            assertNull(gate.tryAcquire("auto-conversation"));
        }

        verify(streamTracker).attach(org.mockito.ArgumentMatchers.eq("auto-conversation"), any(),
                org.mockito.ArgumentMatchers.eq(0L));
        verify(streamTracker, never()).register(any());
    }

    @Test
    void synchronousChatRejectsAutonomousReservationBeforeMutation() {
        when(authentication.getName()).thenReturn("alice");
        ChatController.ChatRequest request = new ChatController.ChatRequest();
        request.setConversationId("auto-conversation");
        request.setMessage("new request");
        try (var autonomous = gate.tryAcquire("auto-conversation")) {
            assertNotNull(autonomous);
            controller.chat(1L, request, 1L, authentication);
            assertNull(gate.tryAcquire("auto-conversation"));
        }

        verify(conversationService, never()).getOrCreateConversation(any(), any(), any(), any());
        verify(agentService, never()).chatWithUsage(any(), any(), any(), any());
    }
}
