package vip.mate.agent.runtime.contract;

public record RuntimeContextUsage(long inputTokens, long outputTokens, long contextWindow) {
    public RuntimeContextUsage {
        if (inputTokens < 0 || outputTokens < 0 || contextWindow < 0) {
            throw new IllegalArgumentException("usage values must be non-negative");
        }
    }
}
