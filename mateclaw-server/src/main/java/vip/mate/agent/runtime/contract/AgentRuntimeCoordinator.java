package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.agent.model.AgentEntity;

import java.nio.file.Path;

/** Selects, validates, and starts the provider chosen by an employee. */
public final class AgentRuntimeCoordinator {
    private final RuntimeProviderRegistry providerRegistry;
    private final RuntimeSessionFactory sessionFactory;

    public AgentRuntimeCoordinator(RuntimeProviderRegistry providerRegistry, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.sessionFactory = new RuntimeSessionFactory(providerRegistry, objectMapper);
    }

    public AgentRuntimeConnection start(AgentEntity agent, String conversationId, String sessionId,
                                        String modelName, Path workspaceRoot, Path workingDirectory) {
        RuntimeSession session = sessionFactory.create(agent, conversationId, sessionId,
                modelName, workspaceRoot, workingDirectory);
        return providerRegistry.resolve(agent.getRuntimeType()).start(session);
    }
}
