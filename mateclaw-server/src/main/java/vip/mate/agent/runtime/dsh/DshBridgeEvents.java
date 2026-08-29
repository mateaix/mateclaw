package vip.mate.agent.runtime.dsh;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DshBridgeEvents {
    private DshBridgeEvents() {}

    public static DshBridgeMessage ready(String sessionId) {
        return DshBridgeMessage.notification("ready", Map.of("sessionId", require(sessionId, "sessionId")));
    }

    public static DshBridgeMessage toolCall(String callId, String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("toolName", require(toolName, "toolName"));
        params.put("arguments", arguments == null ? Map.of() : Map.copyOf(arguments));
        return DshBridgeMessage.request(require(callId, "callId"), "tool/call", params);
    }

    public static DshBridgeMessage approvalAsk(String requestId, String toolName, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("toolName", require(toolName, "toolName"));
        params.put("reason", reason == null ? "" : reason);
        return DshBridgeMessage.request(require(requestId, "requestId"), "approval/ask", params);
    }

    public static DshBridgeMessage subagentLifecycle(String subagentId, String phase,
                                                     Map<String, Object> data) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subagentId", require(subagentId, "subagentId"));
        params.put("phase", require(phase, "phase"));
        if (data != null) params.putAll(Map.copyOf(data));
        return DshBridgeMessage.notification("subagent/lifecycle", params);
    }

    public static DshBridgeMessage toolCancel(String callId) {
        return DshBridgeMessage.notification("tool/cancel",
                Map.of("callId", require(callId, "callId")));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
