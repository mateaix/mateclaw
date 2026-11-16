package vip.mate.agent;

import vip.mate.agent.runtime.state.RunState;

import java.util.Map;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** Cumulative usage already committed by completed runtime nodes, not an estimate of an in-flight call. */
public final class StreamUsageSnapshot {
    private StreamUsageSnapshot() { }

    public static AgentService.StreamDelta from(RunState state) {
        return AgentService.StreamDelta.event("_usage_update", Map.of(
                "promptTokens", state.value(PROMPT_TOKENS, 0),
                "completionTokens", state.value(COMPLETION_TOKENS, 0),
                "cacheReadTokens", state.value(CACHE_READ_TOKENS, 0),
                "cacheWriteTokens", state.value(CACHE_WRITE_TOKENS, 0),
                "reasoningTokens", state.value(REASONING_TOKENS, 0),
                "runtimeModelName", state.value(RUNTIME_MODEL_NAME, ""),
                "runtimeProviderId", state.value(RUNTIME_PROVIDER_ID, "")));
    }
}
