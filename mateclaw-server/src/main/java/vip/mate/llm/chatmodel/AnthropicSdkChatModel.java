package vip.mate.llm.chatmodel;

import com.anthropic.core.Timeout;

import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.retry.support.RetryTemplate;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Official SDK boundary preserving live reasoning and serializable signed thinking history. */
public final class AnthropicSdkChatModel implements ChatModel {
    public static final String THINKING_BLOCKS = "anthropicThinkingBlocks";
    private final AnthropicChatModel delegate;
    private final AnthropicSdkWireInterceptor wire;
    private final RetryTemplate retry;

    private AnthropicSdkChatModel(
            AnthropicChatModel delegate, AnthropicSdkWireInterceptor wire, RetryTemplate retry) {
        this.delegate = delegate;
        this.wire = wire;
        this.retry = retry;
    }

    public static AnthropicSdkChatModel create(
            AnthropicChatOptions options,
            ObservationRegistry observations,
            Map<String, String> headers,
            boolean systemArray) {
        return create(options, observations, headers, systemArray, null);
    }

    public static AnthropicSdkChatModel create(
            AnthropicChatOptions options,
            ObservationRegistry observations,
            Map<String, String> headers,
            boolean systemArray,
            RetryTemplate retry) {
        AnthropicSdkWireInterceptor wire =
                new AnthropicSdkWireInterceptor(
                        headers,
                        systemArray,
                        options.getTimeout() == null
                                ? HttpTimeouts.DEFAULT_READ_TIMEOUT
                                : options.getTimeout());
        AnthropicChatModel delegate =
                AnthropicChatModel.builder()
                        .options(options.mutate().maxRetries(0).build())
                        .observationRegistry(observations)
                        .httpClientBuilderCustomizer(
                                builder ->
                                        builder.timeout(
                                                        Timeout.builder()
                                                                .connect(
                                                                        HttpTimeouts
                                                                                .CONNECT_TIMEOUT)
                                                                .read(options.getTimeout())
                                                                .write(options.getTimeout())
                                                                .request(options.getTimeout())
                                                                .build())
                                                .interceptor(wire))
                        .build();
        return new AnthropicSdkChatModel(delegate, wire, retry);
    }

    @Override
    public AnthropicChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return retry == null ? callOnce(prompt) : retry.execute(context -> callOnce(prompt));
    }

    private ChatResponse callOnce(Prompt prompt) {
        AnthropicThinkingScope scope = new AnthropicThinkingScope(prompt);
        String id = UUID.randomUUID().toString();
        wire.scopes.put(id, scope);
        try {
            return normalize(delegate.call(prepare(prompt, id, false)), scope, false);
        } finally {
            scope.cancellation.close();
            wire.scopes.remove(id);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(
                () -> {
                    AnthropicThinkingScope scope = new AnthropicThinkingScope(prompt);
                    String id = UUID.randomUUID().toString();
                    wire.scopes.put(id, scope);
                    try {
                        return delegate.stream(prepare(prompt, id, true))
                                .map(response -> normalize(response, scope, true))
                                .doOnCancel(scope.cancellation::cancel)
                                .doFinally(signal -> {
                                    scope.cancellation.close();
                                    wire.scopes.remove(id);
                                });
                    } catch (RuntimeException | Error failure) {
                        scope.cancellation.cancel();
                        scope.cancellation.close();
                        wire.scopes.remove(id);
                        throw failure;
                    }
                });
    }

    private Prompt prepare(Prompt prompt, String id, boolean stream) {
        AnthropicChatOptions.Builder builder = getOptions().mutate();
        if (prompt.getOptions() != null) builder.combineWith(prompt.getOptions().mutate());
        if (prompt.getOptions() instanceof ToolCallingChatOptions tools
                && tools.getToolCallbacks() != null) {
            builder.toolCallbacks(tools.getToolCallbacks());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> existing = builder.build().getHttpHeaders();
        if (existing != null) headers.putAll(existing);
        headers.keySet()
                .removeIf(
                        key ->
                                AnthropicSdkWireInterceptor.CORRELATION_HEADER.equalsIgnoreCase(
                                        key));
        headers.put(AnthropicSdkWireInterceptor.CORRELATION_HEADER, id);
        // Read gaps remain bounded by the transport; healthy response bodies have no overall
        // deadline.
        builder.timeout(Duration.ZERO);
        return new Prompt(
                prompt.getInstructions(), builder.httpHeaders(headers).maxRetries(0).build());
    }

    private static ChatResponse normalize(
            ChatResponse response, AnthropicThinkingScope scope, boolean stream) {
        List<Generation> generations = new ArrayList<>();
        List<Generation> source = response.getResults();
        // Spring AI places the final answer/tool calls first, followed by thinking generations.
        if (!stream && !source.isEmpty()) source = List.of(source.getFirst());
        for (Generation generation : source) {
            AssistantMessage original = generation.getOutput();
            Map<String, Object> metadata = new LinkedHashMap<>(original.getMetadata());
            boolean thinkingDelta = Boolean.TRUE.equals(metadata.get("thinking"));
            if (thinkingDelta) {
                String delta = scope.deltas.poll();
                if (delta != null) metadata.put("reasoningContent", delta);
            }
            boolean terminal =
                    !stream
                            || metadata.containsKey("anthropicThinkingContents")
                            || (generation.getMetadata().getFinishReason() != null
                                    && !generation.getMetadata().getFinishReason().isEmpty());
            if (terminal) {
                List<Map<String, Object>> blocks = scope.thinkingBlocks();
                if (!blocks.isEmpty()) metadata.put(THINKING_BLOCKS, blocks);
                if (!stream && !scope.thinkingText().isEmpty())
                    metadata.put("reasoningContent", scope.thinkingText());
            }
            AssistantMessage normalized =
                    original.mutate().properties(metadata).build();
            generations.add(new Generation(normalized, generation.getMetadata()));
        }
        ChatResponseMetadata original = response.getMetadata();
        ChatResponseMetadata metadata = original;
        Usage usage = original.getUsage();
        if (usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            Long cacheRead =
                    scope.cacheRead() == null ? usage.getCacheReadInputTokens() : scope.cacheRead();
            Long cacheWrite =
                    scope.cacheWrite() == null
                            ? usage.getCacheWriteInputTokens()
                            : scope.cacheWrite();
            NativeUsage nativeUsage =
                    new NativeUsage(
                            cacheRead,
                            cacheWrite,
                            Map.of(
                                    "input_tokens",
                                    usage.getPromptTokens(),
                                    "output_tokens",
                                    usage.getCompletionTokens(),
                                    "total_tokens",
                                    usage.getTotalTokens()));
            DefaultUsage corrected =
                    new DefaultUsage(
                            usage.getPromptTokens(),
                            usage.getCompletionTokens(),
                            usage.getTotalTokens(),
                            nativeUsage,
                            cacheRead,
                            cacheWrite);
            metadata =
                    ChatResponseMetadata.builder()
                            .metadata(
                                    original.entrySet().stream()
                                            .collect(
                                                    LinkedHashMap::new,
                                                    (map, entry) ->
                                                            map.put(
                                                                    entry.getKey(),
                                                                    entry.getValue()),
                                                    Map::putAll))
                            .id(original.getId())
                            .model(original.getModel())
                            .rateLimit(original.getRateLimit())
                            .promptMetadata(original.getPromptMetadata())
                            .usage(corrected)
                            .build();
        }
        return new ChatResponse(generations, metadata);
    }

    /** Stable native cache accessors consumed by application usage accounting. */
    public record NativeUsage(
            Long cacheReadInputTokens, Long cacheCreationInputTokens, Map<String, Object> tokens) {}
}
