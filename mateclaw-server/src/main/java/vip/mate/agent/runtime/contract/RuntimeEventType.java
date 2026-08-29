package vip.mate.agent.runtime.contract;

public enum RuntimeEventType {
    RUNTIME_READY,
    ASSISTANT_DELTA,
    THINKING_DELTA,
    TOOL_STARTED,
    TOOL_APPROVAL_REQUIRED,
    TOOL_FINISHED,
    SUBAGENT_STARTED,
    SUBAGENT_FINISHED,
    CONTEXT_USAGE,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
