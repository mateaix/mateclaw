package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeCoordinatorTest {
    @Test
    void startsOnlyAfterSessionValidation() {
        AgentRuntimeConnection connection = mock(AgentRuntimeConnection.class);
        AgentRuntimeProvider provider = mock(AgentRuntimeProvider.class);
        when(provider.type()).thenReturn("dsh");
        when(provider.validate(org.mockito.ArgumentMatchers.any())).thenReturn(RuntimeValidation.success());
        when(provider.start(org.mockito.ArgumentMatchers.any())).thenReturn(connection);

        AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                new RuntimeProviderRegistry(List.of(provider)), new ObjectMapper());
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setWorkspaceId(2L);
        agent.setRuntimeType("dsh");
        agent.setRuntimeConfig("{}");

        assertSame(connection, coordinator.start(agent, "conversation", "session", "model",
                Path.of("/workspace"), Path.of("/workspace/agent")));
    }
}
