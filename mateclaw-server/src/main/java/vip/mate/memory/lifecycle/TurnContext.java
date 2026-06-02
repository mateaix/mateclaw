package vip.mate.memory.lifecycle;

/**
 * Minimal turn-scoped context; built once per turn at AgentService level.
 *
 * @param agentId        the agent ID
 * @param conversationId the conversation ID
 * @param sessionId      session ID (may equal conversationId in Phase 1)
 * @param turnNumber     turn sequence number within the conversation
 * @param userQuery      the current user message
 * @param ownerKey       resolved memory owner key for this turn (e.g.
 *                       "user:42"); drives per-owner memory recall. May be
 *                       null when memory-isolation context is unavailable.
 * @author MateClaw Team
 */
public record TurnContext(
        Long agentId,
        String conversationId,
        String sessionId,
        int turnNumber,
        String userQuery,
        String ownerKey
) {
    /** Backwards-compatible constructor without an owner key (resolves to null). */
    public TurnContext(Long agentId, String conversationId, String sessionId,
                       int turnNumber, String userQuery) {
        this(agentId, conversationId, sessionId, turnNumber, userQuery, null);
    }
}
