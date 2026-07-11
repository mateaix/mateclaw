package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.Map;

/**
 * MCP progress event relay — listens for {@link McpProgressEvent} and forwards to
 * {@link ChatStreamTracker}. Progress events skip the event buffer ({@code skipBuffer=true})
 * and do not participate in SSE reconnect replay. On reconnect, the latest snapshot is
 * read from {@link McpProgressContext} by {@code ChatStreamTracker.attach()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpProgressRelay {

    /**
     * SSE event name constant, agreed upon between frontend and backend.
     */
    public static final String EVENT_TOOL_PROGRESS = "tool_call_progress";

    private final ChatStreamTracker streamTracker;
    private final McpProgressContext progressContext;
    private final ObjectMapper objectMapper;

    @EventListener
    public void onMcpProgress(McpProgressEvent event) {
        try {
            Map<String, Object> data = Map.of(
                    "toolCallId", event.getToolCallId(),
                    "toolName", event.getToolName(),
                    "percent", Math.round(event.getProgress() * 10000.0) / 100.0,
                    "total", event.getTotal() != null ? event.getTotal() : 1.0,
                    "message", event.getMessage() != null ? event.getMessage() : "",
                    "stage", inferStage(event.getProgress())
            );
            String jsonData = objectMapper.writeValueAsString(data);

            // Update snapshot for SSE reconnect
            progressContext.updateSnapshot(event.getConversationId(), event.getToolCallId(), jsonData);

            // Broadcast to SSE (skipBuffer=true, not cached in ring buffer)
            streamTracker.broadcastObject(event.getConversationId(), EVENT_TOOL_PROGRESS, data, true);
        } catch (Exception e) {
            log.warn("Failed to relay MCP progress: {}", e.getMessage());
        }
    }

    /** Infer stage name from progress percentage. */
    private String inferStage(double progress) {
        if (progress <= 0.05) return "prepare";
        if (progress <= 0.95) return "execute";
        return "finalize";
    }
}
