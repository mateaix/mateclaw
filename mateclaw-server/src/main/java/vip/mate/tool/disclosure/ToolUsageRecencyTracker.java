package vip.mate.tool.disclosure;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory recency signal for tool usage. Feeds the budget-driven
 * auto-demotion ranking: never-used tools demote first, then the least
 * recently used ones.
 *
 * <p>Deliberately process-local and unpersisted — this is an advisory
 * ranking, not an audit trail. A restart resets everything to "never used",
 * which merely makes the first demotion pass alphabetical.
 */
@Component
public class ToolUsageRecencyTracker {

    private final Map<String, Long> lastUsedAtMs = new ConcurrentHashMap<>();

    /** Record a successful execution of {@code toolName}. */
    public void recordUse(String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            lastUsedAtMs.put(toolName, System.currentTimeMillis());
        }
    }

    /** @return epoch millis of the last recorded use, or null when never used. */
    public Long lastUsedAt(String toolName) {
        return toolName == null ? null : lastUsedAtMs.get(toolName);
    }
}
