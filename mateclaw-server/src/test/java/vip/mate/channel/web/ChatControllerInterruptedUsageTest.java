package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.AgentRuntime;
import vip.mate.agent.runtime.state.RunStep;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.tool.document.preview.OfficePreviewService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.STREAMED_THINKING;

class ChatControllerInterruptedUsageTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void stopPersistsKnownUsageWithOrWithoutAssistantParts(boolean hasThinking) throws Exception {
        try (Fixture fixture = new Fixture(hasThinking, false)) {
            fixture.start();
            assertTrue(fixture.registered.await(5, TimeUnit.SECONDS));
            fixture.controller.stopStream(fixture.conversation, fixture.auth);
            fixture.assertSaved("stopped");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void errorPersistsKnownUsageWithOrWithoutAssistantParts(boolean hasThinking) throws Exception {
        try (Fixture fixture = new Fixture(hasThinking, true)) {
            fixture.start();
            fixture.assertSaved("failed");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void shutdownSnapshotPersistsUsageEvenWithoutAssistantParts(boolean hasThinking) throws Exception {
        try (Fixture fixture = new Fixture(hasThinking, false)) {
            fixture.start();
            assertTrue(fixture.emergencyRegistered.await(5, TimeUnit.SECONDS));
            fixture.emergency.get().run();
            fixture.assertSaved("interrupted_shutdown");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String conversation = "usage-controller-" + java.util.UUID.randomUUID();
        final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch persisted = new CountDownLatch(1);
        final CountDownLatch emergencyRegistered = new CountDownLatch(1);
        final AtomicReference<Runnable> emergency = new AtomicReference<>();
        final AtomicReference<MessageEntity> assistant = new AtomicReference<>();
        final ChatStreamTracker tracker = spy(new ChatStreamTracker(new ObjectMapper()));
        final Authentication auth = mock(Authentication.class);
        final ChatController controller;

        Fixture(boolean hasThinking, boolean fail) {
            doAnswer(call -> {
                call.callRealMethod(); registered.countDown(); return null;
            }).when(tracker).setDisposable(eq(conversation), any(Disposable.class));
            doAnswer(call -> {
                call.callRealMethod(); emergency.set(call.getArgument(1)); emergencyRegistered.countDown(); return null;
            }).when(tracker).setEmergencySaveCallback(eq(conversation), any(Runnable.class));
            ConversationService conversations = mock(ConversationService.class, call -> {
                if (!call.getMethod().getName().equals("saveMessage")) {
                    return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
                }
                MessageEntity message = new MessageEntity();
                message.setId(1L);
                message.setRole(call.getArgument(1));
                message.setContent(call.getArgument(2));
                message.setPromptTokens(0); message.setCompletionTokens(0);
                if (call.getArguments().length >= 5) message.setStatus(call.getArgument(4));
                if (call.getArguments().length == 13) {
                    message.setPromptTokens(call.getArgument(5));
                    message.setCompletionTokens(call.getArgument(6));
                    message.setCacheReadTokens(call.getArgument(7));
                    message.setCacheWriteTokens(call.getArgument(8));
                    message.setReasoningTokens(call.getArgument(9));
                    message.setRuntimeModel(call.getArgument(10));
                    message.setRuntimeProvider(call.getArgument(11));
                }
                if ("assistant".equals(message.getRole())) {
                    assistant.set(message); persisted.countDown();
                }
                return message;
            });
            when(conversations.isUserMessageAllowed(conversation)).thenReturn(true);
            when(conversations.isConversationOwner(conversation, "alice")).thenReturn(true);
            when(auth.getName()).thenReturn("alice");
            AgentService agents = mock(AgentService.class);
            var state = NativeStreamUsageTest.completedNode(100, 20).state();
            if (hasThinking) state.replace(STREAMED_THINKING, "completed reasoning");
            AgentRuntime runtime = inputs -> Flux.just(new RunStep("reasoning", state))
                    .concatWith(fail ? Flux.error(new IllegalStateException("later node failed")) : Flux.never());
            when(agents.chatStructuredStream(eq(1L), anyString(), eq(conversation), any(), any(), any()))
                    .thenAnswer(call -> new NativeStreamUsageTest.ReactAgent(runtime).chatStructuredStream("hello", conversation));
            controller = new ChatController(agents, conversations, mock(ApprovalWorkflowService.class), tracker,
                    new ObjectMapper(), mock(ConversationCompletionPublisher.class), mock(MemoryOwnerResolver.class),
                    mock(ChatUploadLocationResolver.class), mock(OfficePreviewService.class), mock(ConversationInputQueueStore.class));
        }

        void start() {
            ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
            request.setAgentId(1L); request.setConversationId(conversation); request.setMessage("hello");
            controller.chatStream(request, 1L, auth);
        }

        void assertSaved(String status) throws InterruptedException {
            assertTrue(persisted.await(5, TimeUnit.SECONDS));
            MessageEntity saved = assistant.get();
            assertEquals(status, saved.getStatus());
            assertEquals(100, saved.getPromptTokens());
            assertEquals(20, saved.getCompletionTokens());
            assertEquals(7, saved.getCacheReadTokens());
            assertEquals(3, saved.getCacheWriteTokens());
            assertEquals(5, saved.getReasoningTokens());
            assertEquals("model", saved.getRuntimeModel());
            assertEquals("provider", saved.getRuntimeProvider());
        }

        @Override public void close() {
            tracker.requestStop(conversation);
            ((ExecutorService) ReflectionTestUtils.getField(controller, "sseExecutor")).shutdownNow();
        }
    }
}
