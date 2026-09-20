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
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryRecallTracker;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentServiceDshHistoryTest {
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
        when(connection.prompt(anyString())).thenReturn(Flux.empty());
        ReflectionTestUtils.setField(service, "runtimeCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "dshConversationHistory", history);

        service.chatStructuredStream(1L, "question", "conversation", "user", null, origin).blockLast();

        verify(history).enrich("conversation", "question", "question", origin);
        verify(connection).prompt("history plus question");
        verify(connection).close();
    }
}
