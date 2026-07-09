package vip.mate.tool.mcp.runtime;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe progressToken mapping table.
 * Maintains {@code progressToken → (conversationId, toolCallId, toolName)} mappings,
 * and progress snapshots for SSE reconnect delivery.
 */
@Component
public class McpProgressContext {

    private final Map<String, ProgressEntry> tokenMap = new ConcurrentHashMap<>();

    /** Progress snapshots: conversationId → (toolCallId → latest progress JSON) */
    private final Map<String, Map<String, String>> snapshotMap = new ConcurrentHashMap<>();

    public record ProgressEntry(String conversationId, String toolCallId, String toolName) {}

    public void register(String progressToken, ProgressEntry entry) {
        tokenMap.put(progressToken, entry);
    }

    public ProgressEntry lookup(String progressToken) {
        return tokenMap.get(progressToken);
    }

    public void remove(String progressToken) {
        tokenMap.remove(progressToken);
    }

    /** Update progress snapshot (called on each progress notification). */
    public void updateSnapshot(String conversationId, String toolCallId, String progressJson) {
        snapshotMap.computeIfAbsent(conversationId, k -> new ConcurrentHashMap<>())
                   .put(toolCallId, progressJson);
    }

    /** SSE 重连时获取某个 conversation 下所有进行中的进度快照 */
    public Map<String, String> getSnapshots(String conversationId) {
        Map<String, String> tools = snapshotMap.get(conversationId);
        return tools != null ? Map.copyOf(tools) : Map.of();
    }

    /** Remove snapshot after tool completion. */
    public void removeSnapshot(String conversationId, String toolCallId) {
        Map<String, String> tools = snapshotMap.get(conversationId);
        if (tools != null) {
            tools.remove(toolCallId);
        }
    }
}
