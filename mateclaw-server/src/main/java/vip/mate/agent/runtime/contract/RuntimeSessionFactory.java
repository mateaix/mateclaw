package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.agent.model.AgentEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Builds and validates the runtime-neutral session boundary for an employee turn. */
public final class RuntimeSessionFactory {
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {};

    private final RuntimeProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public RuntimeSessionFactory(RuntimeProviderRegistry providerRegistry, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    public RuntimeSession create(AgentEntity agent, String conversationId, String sessionId,
                                 String modelName, Path workspaceRoot, Path workingDirectory) {
        if (agent == null) throw new IllegalArgumentException("agent is required");
        AgentRuntimeProvider provider = providerRegistry.resolve(agent.getRuntimeType());
        String runtimeType = agent.getRuntimeType() == null || agent.getRuntimeType().isBlank()
                ? RuntimeProviderRegistry.DEFAULT_RUNTIME : agent.getRuntimeType().trim().toLowerCase();

        Path normalizedRoot = normalize(workspaceRoot);
        Path normalizedWorkingDirectory = normalize(workingDirectory);
        if ("dsh".equals(runtimeType)) {
            if (agent.getWorkspaceId() == null) {
                throw new IllegalArgumentException("dsh runtime requires a workspace");
            }
            if (normalizedRoot == null || normalizedWorkingDirectory == null
                    || !normalizedWorkingDirectory.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("dsh working directory must stay inside workspace");
            }
        }

        RuntimeSession session = new RuntimeSession(sessionId, conversationId, agent.getId(),
                agent.getWorkspaceId(), modelName, normalizedWorkingDirectory,
                parseConfig(agent.getRuntimeConfig()));
        RuntimeValidation validation = provider.validate(session);
        if (validation == null || !validation.valid()) {
            String code = validation == null ? "runtime.invalid" : validation.code();
            String message = validation == null ? "runtime provider rejected session" : validation.message();
            throw new IllegalArgumentException(code + ": " + message);
        }
        return session;
    }

    private Map<String, Object> parseConfig(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("runtime config must be a JSON object");
            }
            return objectMapper.convertValue(node, CONFIG_TYPE);
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException iae
                    && "runtime config must be a JSON object".equals(iae.getMessage())) {
                throw iae;
            }
            throw new IllegalArgumentException("runtime config must be valid JSON", e);
        }
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
