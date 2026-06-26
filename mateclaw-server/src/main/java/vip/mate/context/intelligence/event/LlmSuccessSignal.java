package vip.mate.context.intelligence.event;

/**
 * LLM successful call signal event.
 * <p>
 * Published by {@code ReasoningNode} after each {@code streamCall} returns a non-overflow result,
 * consumed asynchronously via the Spring event bus ({@code @Async("signalExecutor")}).
 * <p>
 * Carries data used to update the window probe state machine, the backend diversity tracker, and the pressure inferencer.
 *
 * @param provider        provider ID (from graph state RUNTIME_PROVIDER_ID)
 * @param modelName       model name used by the request (from graph state RUNTIME_MODEL_NAME)
 * @param modelType       model type, e.g. "chat"/"reasoning"/"code" (from graph state RUNTIME_MODEL_TYPE)
 * @param promptTokens    actual prompt token count returned by the API (from StreamResult.promptTokens())
 * @param completionTokens completion token count returned by the API (from StreamResult.completionTokens())
 * @param latencyMs       total elapsed time of this streamCall (timed around the ReasoningNode call)
 * @param traceId         8-char UUID prefix of the graph state (from MateClawStateKeys.TRACE_ID)
 *
 * @author MateClaw Team
 */
public record LlmSuccessSignal(
        String provider,
        String modelName,
        String modelType,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        String traceId
) {}
