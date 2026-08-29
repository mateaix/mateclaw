package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSessionFactoryTest {

    private final RuntimeProviderRegistry registry = new RuntimeProviderRegistry(List.of(
            provider("native", RuntimeValidation.success()),
            provider("dsh", RuntimeValidation.success())
    ));
    private final RuntimeSessionFactory factory = new RuntimeSessionFactory(
            registry, new ObjectMapper());

    @Test
    void createsWorkspaceBoundDshSessionFromPersistedConfig() {
        AgentEntity agent = agent("dsh", "{\"binary\":\"deepseek\"}");

        RuntimeSession session = factory.create(agent, "conversation-1", "session-1",
                "model-a", Path.of("/workspace"), Path.of("/workspace/project"));

        assertEquals("session-1", session.sessionId());
        assertEquals("conversation-1", session.conversationId());
        assertEquals(Map.of("binary", "deepseek"), session.configuration());
    }

    @Test
    void rejectsDshSessionOutsideWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("dsh", "{}"), "conversation-1", "session-1", "model-a",
                Path.of("/workspace"), Path.of("/tmp/outside")));
    }

    @Test
    void rejectsDshSessionWithoutWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("dsh", "{}"), "conversation-1", "session-1", "model-a",
                null, Path.of("/workspace")));
    }

    @Test
    void rejectsNonObjectRuntimeConfig() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("dsh", "[]"), "conversation-1", "session-1", "model-a",
                Path.of("/workspace"), Path.of("/workspace")));
    }

    private static AgentEntity agent(String runtimeType, String runtimeConfig) {
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setWorkspaceId(9L);
        agent.setRuntimeType(runtimeType);
        agent.setRuntimeConfig(runtimeConfig);
        return agent;
    }

    private static AgentRuntimeProvider provider(String type, RuntimeValidation validation) {
        return new AgentRuntimeProvider() {
            @Override public String type() { return type; }
            @Override public RuntimeValidation validate(RuntimeSession session) { return validation; }
            @Override public RuntimeCapabilities capabilities() {
                return new RuntimeCapabilities(true, true, true, true);
            }
            @Override public AgentRuntimeConnection start(RuntimeSession session) {
                throw new UnsupportedOperationException("test provider");
            }
        };
    }
}
