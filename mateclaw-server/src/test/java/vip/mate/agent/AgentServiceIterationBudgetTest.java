package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AgentServiceIterationBudgetTest {
    private final AgentMapper mapper = mock(AgentMapper.class);
    private final AgentService service = new AgentService(mapper, null, null, null, null, null, null);

    @Test
    void omittedBudgetIsStoredAsOneHundredInsteadOfUsingDatabaseDefault() {
        AgentEntity agent = new AgentEntity();
        agent.setName("Research assistant");
        doAnswer(invocation -> {
            AgentEntity inserted = invocation.getArgument(0);
            assertEquals(100, inserted.getMaxIterations());
            return 1;
        }).when(mapper).insert(any(AgentEntity.class));

        assertEquals(100, service.createAgent(agent).getMaxIterations());
        verify(mapper).insert(agent);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10, 150})
    void explicitCreationBudgetIsPreserved(int budget) {
        AgentEntity agent = new AgentEntity();
        agent.setName("Custom assistant");
        agent.setMaxIterations(budget);
        assertEquals(budget, service.createAgent(agent).getMaxIterations());
        verify(mapper).insert(agent);
    }

    @Test
    void editingExistingAgentRetainsItsSmallBudget() {
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setName("Existing assistant");
        agent.setMaxIterations(10);
        agent.setDescription("Updated description");
        when(mapper.selectById(1L)).thenReturn(agent);

        service.updateAgent(agent);

        assertEquals(10, agent.getMaxIterations());
        verify(mapper).updateById(agent);
    }
}
