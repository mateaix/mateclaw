package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class DshBridgeProtocol {
    private final ObjectMapper objectMapper;

    public DshBridgeProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(DshBridgeMessage message) {
        try {
            return objectMapper.writeValueAsString(message) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to encode DSH bridge message", e);
        }
    }

    public DshBridgeMessage decode(String line) {
        if (line == null || line.isBlank()) throw new IllegalArgumentException("bridge message is empty");
        try {
            return objectMapper.readValue(line.trim(), DshBridgeMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid DSH bridge message", e);
        }
    }

    public boolean isNotification(DshBridgeMessage message) {
        return message.id() == null;
    }
}
