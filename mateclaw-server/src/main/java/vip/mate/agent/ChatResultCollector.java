package vip.mate.agent;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/** Collapses a structured agent stream without discarding terminal metadata. */
final class ChatResultCollector {

    private ChatResultCollector() {
    }

    static AgentService.ChatResult collect(Flux<AgentService.StreamDelta> stream) {
        StringBuilder content = new StringBuilder();
        final int[] usage = {0, 0};
        final String[] modelInfo = {null, null};
        final String[] finishReason = {null};
        stream.doOnNext(delta -> {
            if (delta.isEvent() && "_usage_final".equals(delta.eventType())) {
                Map<String, Object> data = delta.eventData() != null ? delta.eventData() : Map.of();
                usage[0] = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                usage[1] = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                Object model = data.get("runtimeModelName");
                Object provider = data.get("runtimeProviderId");
                if (model != null) modelInfo[0] = model.toString();
                if (provider != null) modelInfo[1] = provider.toString();
            } else if (delta.isEvent() && "finish_reason".equals(delta.eventType())) {
                Map<String, Object> data = delta.eventData();
                Object reason = data != null ? data.get("reason") : null;
                if (reason != null) finishReason[0] = reason.toString();
            } else if (delta.content() != null) {
                content.append(delta.content());
            }
        }).blockLast(Duration.ofMinutes(10));
        return new AgentService.ChatResult(content.toString(), usage[0], usage[1],
                modelInfo[0], modelInfo[1], finishReason[0]);
    }
}
