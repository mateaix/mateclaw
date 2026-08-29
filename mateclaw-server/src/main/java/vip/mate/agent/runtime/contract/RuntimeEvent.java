package vip.mate.agent.runtime.contract;

import java.util.Map;

public record RuntimeEvent(
        String sessionId,
        long sequence,
        RuntimeEventType type,
        String text,
        Map<String, Object> data,
        boolean terminal
) {
    public RuntimeEvent {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (terminal != type.terminal()) {
            throw new IllegalArgumentException("terminal flag does not match event type");
        }
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static RuntimeEvent of(String sessionId, long sequence, RuntimeEventType type,
                                  String text, Map<String, Object> data) {
        return new RuntimeEvent(sessionId, sequence, type, text, data, false);
    }

    public static RuntimeEvent terminal(String sessionId, long sequence, RuntimeEventType type,
                                        Map<String, Object> data) {
        return new RuntimeEvent(sessionId, sequence, type, null, data, true);
    }
}
