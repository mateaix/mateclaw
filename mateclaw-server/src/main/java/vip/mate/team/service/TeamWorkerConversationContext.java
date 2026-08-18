package vip.mate.team.service;

/** Server-proven linkage for a delegated team worker conversation. */
public record TeamWorkerConversationContext(
        boolean verified,
        String conversationKind,
        String conversationId,
        Long runId,
        Long taskId,
        Long teamId,
        String leadConversationId,
        Long agentId) {
}
