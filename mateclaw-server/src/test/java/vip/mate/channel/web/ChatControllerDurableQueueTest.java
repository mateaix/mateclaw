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
import static org.mockito.ArgumentMatchers.isNull;
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
        when(authentication.getDetails()).thenReturn(42L);
        when(conversations.isConversationOwner("conv", "alice")).thenReturn(true);
        var conversation = new vip.mate.workspace.conversation.model.ConversationEntity();
        conversation.setConversationId("conv"); conversation.setAgentId(2L); conversation.setWorkspaceId(3L);
        when(conversations.findByConversationId("conv")).thenReturn(conversation);
        when(streams.isRunning("conv")).thenReturn(true);
        when(streams.notifyQueuedInput("conv")).thenReturn(true);
        QueuedInput stored = new QueuedInput(91L, "conv", 2L, "alice", "follow-up",
                List.of(), "queued", null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(queue.enqueue(eq("conv"), eq(2L), eq("alice"), eq("follow-up"),
                eq(List.of()), eq(42L), isNull(), any())).thenReturn(stored);

        ChatController controller = new ChatController(agents, conversations, approvals, streams,
                new ObjectMapper(), mock(ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class), mock(ChatUploadLocationResolver.class),
                mock(OfficePreviewService.class), queue);
        ChatController.InterruptRequest request = new ChatController.InterruptRequest();
        request.setMessage("follow-up");
        request.setAgentId(null); // resolved from the current conversation before snapshotting
        request.setContentParts(List.of());

        var response = controller.interruptStream("conv", request, authentication);

        assertThat(response.getData()).containsEntry("queued", true)
                .containsEntry("queueItemId", "91");
        var order = inOrder(queue, streams);
        order.verify(queue).enqueue(eq("conv"), eq(2L), eq("alice"),
                eq("follow-up"), eq(List.of()), eq(42L), isNull(), any());
        order.verify(streams).notifyQueuedInput("conv");
    }
    @Test
    void queuedStreamCarriesThePersistedAccountInsteadOfThePreviousTurnDisplayName() {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ChatStreamTracker streams = mock(ChatStreamTracker.class);
        ConversationInputQueueStore queue = mock(ConversationInputQueueStore.class);
        var input = new QueuedInput(91L, "conv", 2L, "alice", "queued",
                List.of(), "claimed", "claim", 100L, null, LocalDateTime.now(), LocalDateTime.now(), 42L, 7L);
        when(queue.claimNext(eq("conv"), any(), any())).thenReturn(java.util.Optional.of(input));
        when(queue.consume(eq(91L), any(), any())).thenReturn(true);
        var conversation = new vip.mate.workspace.conversation.model.ConversationEntity();
        conversation.setConversationId("conv"); conversation.setAgentId(2L); conversation.setWorkspaceId(3L);
        when(conversations.findByConversationId("conv")).thenReturn(conversation);
        when(agents.chatStructuredStream(eq(2L), eq("queued"), eq("conv"), any(), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.never());
        var runs = mock(vip.mate.goal.service.GoalApprovalRunService.class);
        when(runs.queuedSelectionStillCurrent(any())).thenReturn(true);
        when(runs.captureSelectedGoal(any())).thenAnswer(invocation ->
                ((vip.mate.agent.context.ChatOrigin) invocation.getArgument(0)).withSelectedGoalId(7L));
        ChatController controller = new ChatController(agents, conversations, mock(ApprovalWorkflowService.class), streams,
                new ObjectMapper(), mock(ConversationCompletionPublisher.class), mock(MemoryOwnerResolver.class),
                mock(ChatUploadLocationResolver.class), mock(OfficePreviewService.class), queue);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "goalApprovalRuns", runs);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "startQueuedMessage", "conv",
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(),
                new java.util.concurrent.atomic.AtomicBoolean(true), "previous-turn-user", "http://localhost");
        var origin = org.mockito.ArgumentCaptor.forClass(vip.mate.agent.context.ChatOrigin.class);
        org.mockito.Mockito.verify(agents).chatStructuredStream(eq(2L), eq("queued"), eq("conv"), any(), any(), origin.capture());
        assertThat(origin.getValue().requesterUserId()).isEqualTo(42L);
        assertThat(origin.getValue().requesterId()).isEqualTo("alice");
        assertThat(origin.getValue().workspaceId()).isEqualTo(3L);
        assertThat(origin.getValue().originMessageId()).isEqualTo(100L);
        assertThat(origin.getValue().selectedGoalId()).isEqualTo(7L);
    }

    @Test
    void oldQueuedInputWithManagedHistoryDoesNotStartAnUnselectedTurn() {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationInputQueueStore queue = mock(ConversationInputQueueStore.class);
        ChatStreamTracker streams = mock(ChatStreamTracker.class);
        var input = new QueuedInput(92L, "conv", 2L, "alice", "old queued text",
                List.of(), "claimed", "claim", null, null,
                LocalDateTime.now(), LocalDateTime.now(), 42L, null);
        when(queue.claimNext(eq("conv"), any(), any())).thenReturn(java.util.Optional.of(input));
        var saved = new vip.mate.workspace.conversation.model.MessageEntity(); saved.setId(101L);
        when(conversations.saveMessage("conv", "user", "old queued text", List.of(), "queued"))
                .thenReturn(saved);
        when(queue.bindMessage(eq(92L), any(), eq(101L), any())).thenReturn(true);
        when(queue.consume(eq(92L), any(), any())).thenReturn(true);
        var conversation = new vip.mate.workspace.conversation.model.ConversationEntity();
        conversation.setConversationId("conv"); conversation.setAgentId(2L); conversation.setWorkspaceId(3L);
        when(conversations.findByConversationId("conv")).thenReturn(conversation);
        var runs = mock(vip.mate.goal.service.GoalApprovalRunService.class);
        when(runs.hasManagedGoalHistory("conv", "2")).thenReturn(true);
        ChatController controller = new ChatController(agents, conversations, mock(ApprovalWorkflowService.class),
                streams, new ObjectMapper(), mock(ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class), mock(ChatUploadLocationResolver.class),
                mock(OfficePreviewService.class), queue);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "goalApprovalRuns", runs);

        var emitter = new RecordingEmitter();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "startQueuedMessage", "conv",
                emitter,
                new java.util.concurrent.atomic.AtomicBoolean(false), "alice", "http://localhost");

        org.mockito.Mockito.verify(conversations).saveMessage("conv", "user", "old queued text", List.of(), "queued");
        org.mockito.Mockito.verify(queue).bindMessage(eq(92L), any(), eq(101L), any());
        org.mockito.Mockito.verify(queue).consume(eq(92L), any(), any());
        assertThat(emitter.events.toString()).contains("queued_input_skipped", "old queued text");
        org.mockito.Mockito.verifyNoInteractions(agents);
    }

    @Test
    void explicitlyUnselectedQueuedInputDoesNotJoinGoalThatAppearedWhileWaiting() {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationInputQueueStore queue = mock(ConversationInputQueueStore.class);
        ChatStreamTracker streams = mock(ChatStreamTracker.class);
        var input = new QueuedInput(94L, "conv", 2L, "alice", "unselected queued text",
                List.of(), "claimed", "claim", null, null,
                LocalDateTime.now(), LocalDateTime.now(), 42L, 0L);
        when(queue.claimNext(eq("conv"), any(), any())).thenReturn(java.util.Optional.of(input));
        var saved = new vip.mate.workspace.conversation.model.MessageEntity(); saved.setId(102L);
        when(conversations.saveMessage("conv", "user", "unselected queued text", List.of(), "queued"))
                .thenReturn(saved);
        when(queue.bindMessage(eq(94L), any(), eq(102L), any())).thenReturn(true);
        when(queue.consume(eq(94L), any(), any())).thenReturn(true);
        when(agents.chatStructuredStream(eq(2L), eq("unselected queued text"), eq("conv"),
                eq("alice"), any(), any())).thenReturn(reactor.core.publisher.Flux.never());
        var conversation = new vip.mate.workspace.conversation.model.ConversationEntity();
        conversation.setConversationId("conv"); conversation.setAgentId(2L); conversation.setWorkspaceId(3L);
        when(conversations.findByConversationId("conv")).thenReturn(conversation);
        var runs = mock(vip.mate.goal.service.GoalApprovalRunService.class);
        when(runs.queuedSelectionStillCurrent(any())).thenReturn(false);
        ChatController controller = new ChatController(agents, conversations, mock(ApprovalWorkflowService.class),
                streams, new ObjectMapper(), mock(ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class), mock(ChatUploadLocationResolver.class),
                mock(OfficePreviewService.class), queue);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "goalApprovalRuns", runs);

        var emitter = new RecordingEmitter();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "startQueuedMessage", "conv",
                emitter, new java.util.concurrent.atomic.AtomicBoolean(false), "alice", "http://localhost");

        org.mockito.Mockito.verify(runs).queuedSelectionStillCurrent(any());
        org.mockito.Mockito.verify(conversations).saveMessage("conv", "user", "unselected queued text", List.of(), "queued");
        org.mockito.Mockito.verify(queue).consume(eq(94L), any(), any());
        assertThat(emitter.events.toString()).contains("queued_input_skipped", "unselected queued text");
        org.mockito.Mockito.verifyNoInteractions(agents);
    }

    @Test
    void skippingAnOldRowStillRunsTheNextQueuedMessage() {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ConversationInputQueueStore queue = mock(ConversationInputQueueStore.class);
        var old = new QueuedInput(92L, "conv", 2L, "alice", "old",
                List.of(), "claimed", "first", 100L, null,
                LocalDateTime.now(), LocalDateTime.now(), 42L, null);
        var next = new QueuedInput(93L, "conv", 2L, "alice", "next",
                List.of(), "claimed", "second", 101L, null,
                LocalDateTime.now(), LocalDateTime.now(), 42L, 0L);
        when(queue.claimNext(eq("conv"), any(), any()))
                .thenReturn(java.util.Optional.of(old), java.util.Optional.of(next));
        when(queue.consume(eq(92L), any(), any())).thenReturn(true);
        when(queue.consume(eq(93L), any(), any())).thenReturn(true);
        when(queue.countQueued("conv")).thenReturn(1);
        var conversation = new vip.mate.workspace.conversation.model.ConversationEntity();
        conversation.setConversationId("conv"); conversation.setAgentId(2L); conversation.setWorkspaceId(3L);
        when(conversations.findByConversationId("conv")).thenReturn(conversation);
        var runs = mock(vip.mate.goal.service.GoalApprovalRunService.class);
        when(runs.hasManagedGoalHistory("conv", "2")).thenReturn(true);
        when(runs.queuedSelectionStillCurrent(any())).thenReturn(true);
        when(runs.captureSelectedGoal(any())).thenAnswer(invocation ->
                ((vip.mate.agent.context.ChatOrigin) invocation.getArgument(0)).withSelectedGoalId(7L));
        when(agents.chatStructuredStream(eq(2L), eq("next"), eq("conv"), eq("alice"), any(), any()))
                .thenReturn(reactor.core.publisher.Flux.never());
        ChatController controller = new ChatController(agents, conversations, mock(ApprovalWorkflowService.class),
                mock(ChatStreamTracker.class), new ObjectMapper(), mock(ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class), mock(ChatUploadLocationResolver.class),
                mock(OfficePreviewService.class), queue);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "goalApprovalRuns", runs);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(controller, "startQueuedMessage", "conv",
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(),
                new java.util.concurrent.atomic.AtomicBoolean(false), "alice", "http://localhost");

        var origin = org.mockito.ArgumentCaptor.forClass(vip.mate.agent.context.ChatOrigin.class);
        org.mockito.Mockito.verify(agents, org.mockito.Mockito.timeout(2000))
                .chatStructuredStream(eq(2L), eq("next"), eq("conv"), eq("alice"), any(), origin.capture());
        assertThat(origin.getValue().selectedGoalId()).isZero();
        org.mockito.Mockito.verify(runs, org.mockito.Mockito.never()).captureSelectedGoal(any());
        org.mockito.Mockito.verify(queue).consume(eq(92L), any(), any());
        org.mockito.Mockito.verify(queue).consume(eq(93L), any(), any());
    }

    private static final class RecordingEmitter extends org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        private final StringBuilder events = new StringBuilder();

        @Override
        public void send(SseEventBuilder builder) {
            builder.build().forEach(part -> events.append(part.getData()));
        }
    }

}
