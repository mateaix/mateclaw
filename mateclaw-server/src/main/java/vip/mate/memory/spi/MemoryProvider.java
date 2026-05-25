package vip.mate.memory.spi;

import java.util.Collections;
import java.util.List;

/**
 * Memory provider SPI.
 * <p>
 * Pluggable interface for memory backends. Each provider contributes to:
 * <ul>
 *   <li>System prompt assembly (frozen at agent build time)</li>
 *   <li>Per-turn context prefetch (injected before LLM call)</li>
 *   <li>Post-turn sync (async persistence)</li>
 *   <li>Agent tools (Spring AI @Tool beans)</li>
 * </ul>
 *
 * @author MateClaw Team
 */
public interface MemoryProvider {

    /**
     * Unique provider identifier, e.g. "builtin", "structured", "session_search".
     */
    String id();

    /**
     * Ordering for system prompt assembly and lifecycle dispatch.
     * Lower values run first. Builtin = 0.
     */
    default int order() {
        return 100;
    }

    /**
     * Runtime availability check. Should not make network calls.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * System prompt contribution. Called once at agent build time,
     * result is frozen as a snapshot for the session lifetime.
     * Mid-session memory writes update the DB but NOT this snapshot
     * (preserves prompt cache efficiency).
     *
     * @param agentId the agent ID
     * @return text to include in system prompt, or empty string to skip
     */
    default String systemPromptBlock(Long agentId) {
        return "";
    }

    /**
     * Pre-turn context recall. Called before each LLM API call.
     * Return relevant context to inject, or empty string.
     * Should be fast; use background threads for actual recall.
     *
     * @param agentId   the agent ID
     * @param userQuery the current user message
     * @return context text to inject, wrapped in memory-context fence by MemoryManager
     */
    default String prefetch(Long agentId, String userQuery) {
        return "";
    }

    /**
     * Post-turn sync. Called after LLM response is available.
     * Should be non-blocking (async).
     */
    default void syncTurn(Long agentId, String conversationId,
                          String userMessage, String assistantReply) {
    }

    /**
     * Spring AI @Tool beans this provider wants to expose to the agent.
     * These are collected by MemoryManager and added to the tool set.
     */
    default List<Object> getToolBeans() {
        return Collections.emptyList();
    }

    /**
     * Session end hook. Called when a conversation completes.
     */
    default void onSessionEnd(Long agentId, String conversationId) {
    }

    /**
     * Pre-compression hook. Called before context window compression
     * discards old messages. Return text to preserve in compression summary.
     */
    default String onPreCompress(Long agentId, List<?> messages) {
        return "";
    }

    /**
     * Notification that a memory write occurred. Called after canonical memory
     * files (structured/*.md, MEMORY.md) are updated.
     *
     * <p>Phase 2: SOUL auto-evolution subscribes to this.
     *
     * @param agentId the agent ID
     * @param target  which file was written (e.g. "MEMORY.md", "structured/user_pref.md")
     * @param action  what happened ("append", "update", "consolidate")
     * @param content the written content
     */
    default void onMemoryWrite(Long agentId, String target, String action, String content) {
    }

    /**
     * Warm up provider internal state (embeddings, index handles, connection pools).
     * Called when an agent session is likely to start. Providers decide what to cache.
     *
     * <p>Phase 2: provider internal-state cache (not recall text cache — F2).
     *
     * @param agentId the agent ID
     */
    default void warmup(Long agentId) {
    }

    /**
     * Evict cached internal state for an agent. Called on agent deactivation or
     * memory pressure.
     *
     * @param agentId the agent ID
     */
    default void evict(Long agentId) {
    }
}
