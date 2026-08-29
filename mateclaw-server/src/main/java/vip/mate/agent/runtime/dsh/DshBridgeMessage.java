package vip.mate.agent.runtime.dsh;

import java.util.Map;

public record DshBridgeMessage(
        String id,
        String method,
        Map<String, Object> params,
        Object result,
        String errorCode,
        String errorMessage
) {
    public DshBridgeMessage {
        if (method == null || method.isBlank()) throw new IllegalArgumentException("method is required");
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static DshBridgeMessage request(String id, String method, Map<String, Object> params) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("request id is required");
        return new DshBridgeMessage(id, method, params, null, null, null);
    }

    public static DshBridgeMessage notification(String method, Map<String, Object> params) {
        return new DshBridgeMessage(null, method, params, null, null, null);
    }
}
