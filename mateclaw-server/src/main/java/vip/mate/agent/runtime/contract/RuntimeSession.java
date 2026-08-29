package vip.mate.agent.runtime.contract;

import java.nio.file.Path;
import java.util.Map;

public record RuntimeSession(
        String sessionId,
        String conversationId,
        Long agentId,
        Long workspaceId,
        String modelName,
        Path workingDirectory,
        Map<String, Object> configuration
) {
    public RuntimeSession {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (conversationId == null || conversationId.isBlank()) throw new IllegalArgumentException("conversationId is required");
        configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    }
}
