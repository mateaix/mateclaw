package vip.mate.llm.chatmodel;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static singleton cache for MiMo-style {@code reasoning_content} replay.
 *
 * <p>MiMo (and similar providers) require {@code reasoning_content} on assistant
 * messages that carry {@code tool_calls}. When the conversation spans multiple
 * turns, the cache replays the <em>real</em> reasoning content from prior
 * responses instead of injecting empty strings, preserving model context.
 *
 * <h2>Key design</h2>
 * <ul>
 *   <li>Key: sorted, concatenated tool_call IDs from the assistant message.
 *       Tool call IDs are unique per response, so this key naturally
 *       disambiguates across turns.</li>
 *   <li>TTL: 24 hours (configurable). Entries older than TTL are lazily evicted
 *       on access and periodically during {@link #store}.</li>
 *   <li>Max entries: 10,000. Oldest entries evicted when exceeded.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <ol>
 *   <li><b>Store</b>: after a streaming response completes, call
 *       {@link #store} with the tool_call IDs and reasoning content.</li>
 *   <li><b>Retrieve</b>: during request patching, call {@link #get} for
 *       cross-turn assistant messages to fill in cached reasoning.</li>
 * </ol>
 *
 * @author MateClaw Team
 */
public final class ReasoningContentCache {

    private static final long DEFAULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final int DEFAULT_MAX_ENTRIES = 10_000;
    private static final long EVICT_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    private static final ConcurrentHashMap<String, Entry> MAP = new ConcurrentHashMap<>();
    private static volatile long lastEvictMs = System.currentTimeMillis();

    private ReasoningContentCache() {}

    /**
     * Cache reasoning content for a set of tool_call IDs.
     *
     * @param toolCallIds     tool call IDs from the assistant message (must not be null/empty)
     * @param reasoningContent the real reasoning content to cache (must not be blank)
     */
    public static void store(List<String> toolCallIds, String reasoningContent) {
        if (toolCallIds == null || toolCallIds.isEmpty()) return;
        if (reasoningContent == null || reasoningContent.isBlank()) return;

        String key = makeKey(toolCallIds);
        MAP.put(key, new Entry(reasoningContent, System.currentTimeMillis()));

        maybeEvict();
    }

    /**
     * Retrieve cached reasoning content for the given tool_call IDs.
     *
     * @return cached reasoning content, or {@code null} if not found or expired
     */
    public static String get(List<String> toolCallIds) {
        if (toolCallIds == null || toolCallIds.isEmpty()) return null;

        String key = makeKey(toolCallIds);
        Entry entry = MAP.get(key);
        if (entry == null) return null;

        if (System.currentTimeMillis() - entry.storedAtMs > DEFAULT_MAX_AGE_MS) {
            MAP.remove(key);
            return null;
        }
        return entry.reasoningContent;
    }

    /** Clear all cached entries. */
    public static void clear() {
        MAP.clear();
    }

    /** Current cache size (for diagnostics). */
    public static int size() {
        return MAP.size();
    }

    private static String makeKey(List<String> toolCallIds) {
        return String.join("|", toolCallIds.stream().sorted().toList());
    }

    private static void maybeEvict() {
        long now = System.currentTimeMillis();
        if (now - lastEvictMs < EVICT_INTERVAL_MS) return;
        lastEvictMs = now;

        // Remove expired entries
        MAP.entrySet().removeIf(e -> now - e.getValue().storedAtMs > DEFAULT_MAX_AGE_MS);

        // Remove oldest if over limit
        if (MAP.size() > DEFAULT_MAX_ENTRIES) {
            MAP.entrySet().stream()
                    .sorted((a, b) -> Long.compare(a.getValue().storedAtMs, b.getValue().storedAtMs))
                    .limit(MAP.size() - DEFAULT_MAX_ENTRIES)
                    .forEach(e -> MAP.remove(e.getKey()));
        }
    }

    private record Entry(String reasoningContent, long storedAtMs) {}
}
