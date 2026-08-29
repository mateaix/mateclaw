package vip.mate.agent.runtime.contract;

public record RuntimeCapabilities(
        boolean supportsCancellation,
        boolean supportsApprovals,
        boolean supportsSubagents,
        boolean supportsContextUsage
) {}
