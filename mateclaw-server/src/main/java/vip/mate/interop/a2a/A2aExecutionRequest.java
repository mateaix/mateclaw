package vip.mate.interop.a2a;

public record A2aExecutionRequest(
        String taskId,
        String contextId,
        String message,
        Long agentId,
        Long workspaceId,
        String username,
        Long userId
) {
}
