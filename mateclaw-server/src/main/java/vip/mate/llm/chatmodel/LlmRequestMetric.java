package vip.mate.llm.chatmodel;

import java.time.LocalDateTime;

public record LlmRequestMetric(
    LocalDateTime timestamp,
    String providerId,
    String modelName,
    boolean isStreaming,
    Long firstTokenLatencyMs, // null for non-streaming
    long overallLatencyMs
) {}
