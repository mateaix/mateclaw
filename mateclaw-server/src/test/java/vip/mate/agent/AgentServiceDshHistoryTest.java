package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeCoordinator;
import vip.mate.agent.runtime.dsh.DshConversationHistory;
import vip.mate.agent.runtime.RunningConversationRegistry;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryRecallTracker;

import static org.junit.jupiter.api.Assertions.*;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentServiceDshHistoryTest {
    @Test void dshAsyncSuccessDoesNotClearParentOrCompletionWorkerThinking() throws Exception { asyncContext(false); }
    @Test void dshAsyncErrorDoesNotClearParentOrCompletionWorkerThinking() throws Exception { asyncContext(true); }
    private void asyncContext(boolean error) throws Exception {
        var mapper = mock(AgentMapper.class); var agent = new AgentEntity(); agent.setId(1L); agent.setRuntimeType("dsh"); agent.setModelName("model");
        when(mapper.selectById(1L)).thenReturn(agent);
        var memory = new MemoryProperties(); memory.setLifecycleMediatorEnabled(false);
        var service = new AgentService(mapper, null, mock(MemoryRecallTracker.class), null, memory, null, null);
        var coordinator = mock(AgentRuntimeCoordinator.class); var connection = mock(AgentRuntimeConnection.class); var history = mock(DshConversationHistory.class);
        var origin = ChatOrigin.EMPTY.withConversationId("child").withOriginMessageId(12L);
        var parent = ChatOrigin.EMPTY.withConversationId("parent"); var completion = ChatOrigin.EMPTY.withConversationId("completion-worker");
        when(history.enrich("child", "question", "question", origin)).thenReturn("captured-history");
        when(coordinator.start(eq(agent), eq("child"), eq("child"), eq("model"), any(), any())).thenReturn(connection);
        var executor = Executors.newSingleThreadExecutor(); var finished = new CountDownLatch(1); var workerFailure = new AtomicReference<Throwable>();
        when(connection.prompt("captured-history")).thenReturn(Flux.<RuntimeEvent>create(sink -> executor.execute(() -> {
            ChatOriginHolder.set(completion); ThinkingLevelHolder.set("completion-thinking");
            try {
                if (error) sink.error(new IllegalStateException("dsh-failure"));
                else { sink.next(RuntimeEvent.of("child", 1, RuntimeEventType.ASSISTANT_DELTA, "dsh-answer", Map.of())); sink.complete(); }
                assertEquals(completion, ChatOriginHolder.get());
                assertEquals("completion-thinking", ThinkingLevelHolder.get(), "DSH completion must not remove unrelated worker thinking");
            } catch (Throwable failure) { workerFailure.set(failure); }
            finally { ChatOriginHolder.clear(); ThinkingLevelHolder.clear(); finished.countDown(); }
        })));
        ReflectionTestUtils.setField(service, "runtimeCoordinator", coordinator); ReflectionTestUtils.setField(service, "dshConversationHistory", history);
        ChatOriginHolder.set(parent); ThinkingLevelHolder.set("parent-thinking");
        try {
            var stream = service.chatStructuredStream(1L, "question", "child", "requester", "high", origin);
            assertEquals(parent, ChatOriginHolder.get()); assertEquals("parent-thinking", ThinkingLevelHolder.get());
            if (error) assertEquals("dsh-failure", assertThrows(IllegalStateException.class, stream::blockLast).getMessage());
            else assertEquals("dsh-answer", stream.blockLast().content());
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(parent, ChatOriginHolder.get()); assertEquals("parent-thinking", ThinkingLevelHolder.get());
            if(workerFailure.get()!=null) throw new AssertionError(workerFailure.get());
            verify(history).enrich("child", "question", "question", origin); verify(connection).prompt("captured-history"); verify(connection).close();
        } finally { ChatOriginHolder.clear(); ThinkingLevelHolder.clear(); executor.shutdownNow(); }
    }

    @Test
    void dshBranchPassesCapturedOriginAndHistoryToRuntime() {
        var mapper = mock(AgentMapper.class);
        var agent = new AgentEntity();
        agent.setId(1L);
        agent.setRuntimeType("dsh");
        agent.setModelName("model");
        when(mapper.selectById(1L)).thenReturn(agent);
        var memory = new MemoryProperties();
        memory.setLifecycleMediatorEnabled(false);
        var service = new AgentService(mapper, null, mock(MemoryRecallTracker.class), null, memory, null, null);
        var coordinator = mock(AgentRuntimeCoordinator.class);
        var connection = mock(AgentRuntimeConnection.class);
        var history = mock(DshConversationHistory.class);
        var origin = ChatOrigin.EMPTY.withOriginMessageId(12L);
        when(history.enrich("conversation", "question", "question", origin)).thenReturn("history plus question");
        when(coordinator.start(eq(agent), eq("conversation"), eq("conversation"), eq("model"), any(), any()))
                .thenReturn(connection);
        var registry = mock(RunningConversationRegistry.class);
        doAnswer(invocation -> {
            assertEquals(origin, ChatOriginHolder.get());
            assertEquals("high", ThinkingLevelHolder.get());
            return null;
        }).when(registry).register("conversation", 1L);
        when(connection.prompt(anyString())).thenReturn(Flux.empty());
        ReflectionTestUtils.setField(service, "runtimeCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "dshConversationHistory", history);
        ReflectionTestUtils.setField(service, "runningConversationRegistry", registry);

        service.chatStructuredStream(1L, "question", "conversation", "user", "high", origin).blockLast();

        verify(history).enrich("conversation", "question", "question", origin);
        verify(connection).prompt("history plus question");
        verify(connection).close();
        verify(registry).register("conversation", 1L);
    }
}
