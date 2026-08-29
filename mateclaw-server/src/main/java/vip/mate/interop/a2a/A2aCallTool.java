package vip.mate.interop.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class A2aCallTool {

    private final A2aPeerAdapter peerAdapter;
    private final ObjectMapper objectMapper;

    @Tool(name = "call_a2a_agent", description = "Call another A2A-compatible agent. The config JSON must include url and may include headers.")
    public String callA2aAgent(
            @ToolParam(description = "Message to send to the peer agent") String message,
            @ToolParam(description = "Optional peer conversation context id", required = false) String contextId,
            @ToolParam(description = "Optional peer skill id", required = false) String skillId,
            @ToolParam(description = "JSON object: {\"url\":\"https://peer/api/a2a\",\"headers\":{\"Authorization\":\"Bearer ...\"},\"stream\":false}") String config
    ) {
        try {
            Map<String, Object> cfg = parseConfig(config);
            String url = String.valueOf(cfg.getOrDefault("url", "")).trim();
            if (url.isBlank()) {
                return "Error: config.url is required.";
            }
            Map<String, String> headers = headers(cfg.get("headers"));
            boolean stream = Boolean.TRUE.equals(cfg.get("stream"));
            A2aPeerAdapter.PeerResult result = stream
                    ? peerAdapter.stream(url, message, contextId, skillId, headers)
                    : peerAdapter.sendBlocking(url, message, contextId, skillId, headers);
            if (stream && !result.frames().isEmpty()) {
                return objectMapper.writeValueAsString(result.frames());
            }
            return result.body();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private Map<String, Object> parseConfig(String config) throws Exception {
        if (config == null || config.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(config, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static Map<String, String> headers(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return out;
    }
}
