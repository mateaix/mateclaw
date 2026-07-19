package vip.mate.plugin.api.memory;

import java.util.Collections;
import java.util.List;

/**
 * Memory provider interface for plugins.
 * <p>
 * Mirrors the platform's internal MemoryProvider SPI with no server-internal dependencies.
 * The platform wraps it via a bridge.
 *
 * @author MateClaw Team
 */
public interface PluginMemoryProvider {

    /**
     * Unique provider identifier, e.g. "vector_memory", "graph_memory".
     */
    String id();

    /**
     * Ordering for system prompt assembly and lifecycle dispatch.
     * Lower values run first. Builtin = 0.
     */
    default int order() {
        return 200;
    }

    /**
     * Runtime availability check. Should not make network calls.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * System prompt contribution. Called once at agent build time.
     *
     * @param agentId the agent ID
     * @return text to include in system prompt, or empty string to skip
     */
    default String systemPromptBlock(Long agentId) {
        return "";
    }

    /**
     * Pre-turn context recall. Called before each LLM API call.
     *
     * @param agentId   the agent ID
     * @param userQuery the current user message
     * @return context text to inject, or empty string
     */
    default String prefetch(Long agentId, String userQuery) {
        return "";
    }

    /**
     * Pre-turn context recall with per-owner isolation. Called by the platform
     * when an owner key (e.g. {@code "user:42"}, {@code "feishu:sender_abc"})
     * is resolved for the current conversation.
     * <p>
     * Default implementation degrades to the two-arg variant, dropping the
     * owner key. External providers that need per-owner recall (e.g. Mem0)
     * should override this to use {@code ownerKey} as their per-user identifier.
     *
     * @param agentId   the agent ID
     * @param userQuery the current user message
     * @param ownerKey  memory owner key (e.g. {@code "user:42"}), or null if unknown
     * @return context text to inject, or empty string
     */
    default String prefetch(Long agentId, String userQuery, String ownerKey) {
        return prefetch(agentId, userQuery);
    }

    /**
     * Post-turn sync. Called after LLM response is available.
     * Should be non-blocking (async).
     */
    default void syncTurn(Long agentId, String conversationId,
                          String userMessage, String assistantReply) {
    }

    /**
     * Tool beans this provider wants to expose to the agent.
     */
    default List<Object> getToolBeans() {
        return Collections.emptyList();
    }

    /**
     * Session end hook.
     */
    default void onSessionEnd(Long agentId, String conversationId) {
    }
}
