package vip.mate.tool.guard.model;

import java.util.Map;

/**
 * Standard tool invocation context shared by every Guardian.
 * <p>
 * The {@code workspaceId} field was added so that {@code ApprovalGrantResolver}
 * can scope grant lookups by workspace without forcing a DB query inside the
 * resolver. Callers that already know the workspace pass it explicitly via
 * {@link #of(String, Map, String, String, String, String, String, Long)}.
 * Legacy callers using {@link #of(String, String, String, String)} receive
 * {@code workspaceId = null}; the resolver then conservatively falls back to
 * the existing human-approval path.
 */
public record ToolInvocationContext(
        String toolName,
        Map<String, Object> parameters,
        String rawArguments,
        String conversationId,
        String agentId,
        String channelType,
        String userId,
        Long workspaceId
) {

    /**
     * Legacy factory: workspaceId resolved lazily downstream (sets {@code null} here).
     * Kept verbatim so existing call sites and tests continue to compile.
     */
    public static ToolInvocationContext of(String toolName, String rawArguments,
                                           String conversationId, String agentId) {
        return new ToolInvocationContext(
                toolName, Map.of(), rawArguments, conversationId, agentId,
                null, null, null);
    }

    /**
     * Full factory: preferred path used by {@code ToolExecutionExecutor.evaluateGuard()}
     * once {@code WorkspaceLookupCache.resolveByConversation(...)} has resolved the
     * workspace.
     */
    public static ToolInvocationContext of(String toolName, Map<String, Object> parameters,
                                           String rawArguments, String conversationId,
                                           String agentId, String channelType, String userId,
                                           Long workspaceId) {
        return new ToolInvocationContext(
                toolName,
                parameters != null ? parameters : Map.of(),
                rawArguments, conversationId, agentId,
                channelType, userId, workspaceId);
    }
}
