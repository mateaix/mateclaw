package vip.mate.execution.evidence.model;

public record ExecutionIdentity(Long workspaceId, String conversationId, String runtimeKind, String runtimeSessionId,
        String invocationKey, String logicalCallId, int attemptNo, String providerToolCallId,
        String toolName, Long goalId, String goalAttemptId, Long teamRunId, Long teamTaskId,
        Long cronRunId, String approvalId, String ownerFence) { }
