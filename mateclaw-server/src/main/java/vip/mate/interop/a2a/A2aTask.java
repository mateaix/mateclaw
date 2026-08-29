package vip.mate.interop.a2a;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record A2aTask(
        String id,
        String contextId,
        String tenant,
        String state,
        String message,
        List<Map<String, Object>> artifacts,
        boolean terminal,
        Instant createdAt,
        Instant updatedAt
) {

    private static final List<String> TERMINAL_STATES = List.of("completed", "canceled", "failed");

    public A2aTask {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public static A2aTask submitted(String id, String contextId, String tenant) {
        Instant now = Instant.now();
        return new A2aTask(id, contextId, tenant, "submitted", null, List.of(), false, now, now);
    }

    public A2aTask withStatus(String state, String message, boolean terminal) {
        boolean finalState = terminal || TERMINAL_STATES.contains(state);
        return new A2aTask(id, contextId, tenant, state, message, artifacts, finalState, createdAt, Instant.now());
    }

    public A2aTask withUpdatedAt(Instant updatedAt) {
        return new A2aTask(id, contextId, tenant, state, message, artifacts, terminal, createdAt, updatedAt);
    }

    public A2aTask withArtifact(String text, boolean append) {
        Map<String, Object> artifact = Map.of(
                "artifactId", "artifact-" + (artifacts.size() + 1),
                "parts", List.of(Map.of("kind", "text", "text", text != null ? text : ""))
        );
        List<Map<String, Object>> next = append
                ? new ArrayList<>(artifacts)
                : new ArrayList<>();
        next.add(artifact);
        return new A2aTask(id, contextId, tenant, state, message, next, terminal, createdAt, Instant.now());
    }

    public Map<String, Object> toMap() {
        return Map.of(
                "id", id,
                "contextId", contextId,
                "status", Map.of(
                        "state", state,
                        "message", messageAsA2aMessage(message)
                ),
                "artifacts", artifacts
        );
    }

    private static Object messageAsA2aMessage(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        return Map.of(
                "role", "agent",
                "parts", List.of(Map.of("kind", "text", "text", text))
        );
    }
}
