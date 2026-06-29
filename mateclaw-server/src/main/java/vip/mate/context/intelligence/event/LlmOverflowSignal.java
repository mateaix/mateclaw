package vip.mate.context.intelligence.event;

/**
 * LLM overflow call signal event.
 * <p>
 * Published by {@code ReasoningNode} when {@code streamCall} returns {@code isPromptTooLong()=true},
 * consumed synchronously via the Spring event bus (without {@code @Async}).
 * <p>
 * Synchronous handling ensures {@code confidenceUpper} is updated before retry,
 * which affects binary search convergence on consecutive PTL events.
 *
 * @param provider        provider ID
 * @param modelName       model name used by the request
 * @param modelType       model type, e.g. "chat"/"reasoning"/"code"
 * @param attemptedTokens estimated prompt token count before overflow
 * @param traceId         8-char UUID prefix of the graph state
 *
 * @author MateClaw Team
 */
public record LlmOverflowSignal(
        String provider,
        String modelName,
        String modelType,
        int attemptedTokens,
        String traceId
) {}
