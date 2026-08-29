package vip.mate.agent.runtime.dsh;

import vip.mate.agent.runtime.contract.RuntimeSession;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DshBridgeRequests {
    private DshBridgeRequests() {}

    public static DshBridgeMessage sessionOpen(RuntimeSession session, Map<String, Object> policy) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sessionId", session.sessionId());
        params.put("conversationId", session.conversationId());
        putIfPresent(params, "agentId", session.agentId());
        putIfPresent(params, "workspaceId", session.workspaceId());
        putIfPresent(params, "model", session.modelName());
        putIfPresent(params, "cwd", session.workingDirectory() == null
                ? null : session.workingDirectory().toString());
        params.putAll(session.configuration());
        if (policy != null) params.put("policy", Map.copyOf(policy));
        return DshBridgeMessage.request("open-" + session.sessionId(), "session/open", params);
    }

    public static DshBridgeMessage prompt(String requestId, String message) {
        require(requestId, "requestId");
        if (message == null) throw new IllegalArgumentException("message is required");
        return DshBridgeMessage.request(requestId, "session/prompt", Map.of("message", message));
    }

    public static DshBridgeMessage cancel(String requestId, String sessionId) {
        require(requestId, "requestId");
        require(sessionId, "sessionId");
        return DshBridgeMessage.request(requestId, "session/cancel", Map.of("sessionId", sessionId));
    }

    public static DshBridgeMessage policyUpdate(String requestId, Map<String, Object> policy) {
        require(requestId, "requestId");
        return DshBridgeMessage.request(requestId, "policy/update",
                Map.of("policy", policy == null ? Map.of() : Map.copyOf(policy)));
    }

    public static DshBridgeMessage contextUsage(String requestId, String sessionId) {
        require(requestId, "requestId");
        require(sessionId, "sessionId");
        return DshBridgeMessage.request(requestId, "context/usage", Map.of("sessionId", sessionId));
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }
}
