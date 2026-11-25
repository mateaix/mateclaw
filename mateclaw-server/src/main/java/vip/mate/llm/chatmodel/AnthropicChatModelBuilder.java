package vip.mate.llm.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.cache.AnthropicCacheOptionsFactory;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.http.HttpClient;
import java.util.Map;
import org.springframework.http.client.reactive.JdkClientHttpConnector;

/**
 * Strategy implementation for {@link ModelProtocol#ANTHROPIC_MESSAGES}.
 *
 * <p>Owns the full Anthropic construction logic — API client + chat options
 * including the extended-thinking budget mapping (low/medium/high/max →
 * 4k/8k/16k/32k thinking tokens) and prompt-cache options.</p>
 */
@Slf4j
@Component
public class AnthropicChatModelBuilder implements ChatModelBuilder {

    private final ModelProviderService modelProviderService;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;
    private final AnthropicCacheOptionsFactory anthropicCacheOptionsFactory;

    public AnthropicChatModelBuilder(
            ModelProviderService modelProviderService,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            AnthropicCacheOptionsFactory anthropicCacheOptionsFactory) {
        this.modelProviderService = modelProviderService;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.observationRegistryProvider = observationRegistryProvider;
        this.anthropicCacheOptionsFactory = anthropicCacheOptionsFactory;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.ANTHROPIC_MESSAGES;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        AnthropicChatOptions options = buildClientOptions(model, provider);
        return AnthropicSdkChatModel.create(options,
                observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP), Map.of(), false, retry);
    }

    AnthropicChatOptions buildClientOptions(ModelConfigEntity model, ModelProviderEntity provider) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.anthropic_not_configured",
                    "Anthropic provider needs a valid API key and base URL");
        }
        if (!modelProviderService.hasUsableApiKey(provider.getApiKey())) {
            throw new MateClawException("err.agent.anthropic_key_invalid", "Invalid Anthropic API key");
        }
        AnthropicChatOptions.Builder builder = buildAnthropicOptions(model).mutate()
                .apiKey(provider.getApiKey().trim())
                .timeout(HttpTimeouts.resolveReadTimeout(model.getRequestTimeoutSeconds())).maxRetries(0);
        if (StringUtils.hasText(provider.getBaseUrl())) builder.baseUrl(provider.getBaseUrl().trim());
        return builder.build();
    }

    /**
     * Substrings used to detect Claude 4.7 model variants.
     * Claude 4.7 returns HTTP 400 if any of {@code temperature}, {@code top_p},
     * or {@code top_k} are set to non-default values, AND introduces an
     * "xhigh" thinking effort level between high and max.
     */
    static boolean isClaude47(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.toLowerCase();
        // Require the "claude" token to avoid false positives like "gpt-4-7"
        // matching. Match both hyphenated (claude-opus-4-7, anthropic/claude-opus-4-7)
        // and dotted (claude-opus-4.7, anthropic/claude-opus-4.7 via OpenRouter)
        // forms. Also tolerates date-stamped variants (claude-opus-4-7-20260415).
        if (!lower.contains("claude")) return false;
        return lower.contains("4-7") || lower.contains("4.7");
    }

    /**
     * Detect Claude 4.8 model variants (including the {@code -fast} sibling).
     * Claude 4.8 inherits 4.7's strict API contract: temperature / top_p /
     * top_k must be unset, and the "xhigh" thinking tier is available.
     */
    static boolean isClaude48(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.toLowerCase();
        if (!lower.contains("claude")) return false;
        // Matches claude-opus-4-8, claude-opus-4.8, the "-fast" sibling
        // (claude-opus-4-8-fast / claude-opus-4.8-fast), and OpenRouter
        // prefixed forms (anthropic/claude-opus-4-8...).
        return lower.contains("4-8") || lower.contains("4.8");
    }

    /**
     * Detect the Claude Fable model line (e.g. {@code claude-fable-5}).
     * Fable is a reasoning-first family that follows the same strict API
     * contract as Claude 4.7+: temperature / top_p / top_k must be unset
     * (any non-default value returns HTTP 400) and the "xhigh" adaptive
     * thinking tier is available. Matching on the {@code claude-fable} token
     * covers the direct-API id, the OpenRouter-prefixed form
     * ({@code anthropic/claude-fable-5}), and future {@code claude-fable-N}
     * revisions without a per-version code change.
     */
    static boolean isClaudeFable(String modelName) {
        if (modelName == null) return false;
        return modelName.toLowerCase().contains("claude-fable");
    }

    /**
     * True for any modern Claude model that drops temperature / top_p / top_k
     * and exposes the "xhigh" thinking tier between high and max — the Claude
     * 4.7 / 4.8 generations and the Fable reasoning line.
     */
    static boolean isClaude47OrLater(String modelName) {
        return isClaude47(modelName) || isClaude48(modelName) || isClaudeFable(modelName);
    }

    AnthropicChatOptions buildAnthropicOptions(ModelConfigEntity runtimeModel) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        String modelName = runtimeModel.getModelName();
        if (StringUtils.hasText(modelName)) {
            builder.model(modelName);
        }

        // Extended thinking — request-level depth from ThinkingLevelHolder
        String thinkingLevel = ThinkingLevelHolder.get();
        boolean thinkingEnabled = thinkingLevel != null && !"off".equalsIgnoreCase(thinkingLevel);

        if (thinkingEnabled) {
            // The Anthropic 3.x API no longer accepts sampling overrides.
            int budgetTokens = switch (thinkingLevel.toLowerCase()) {
                case "low" -> 4096;
                case "medium" -> 8192;
                case "high" -> 16384;
                case "xhigh" -> 24576;  // 4.7+ only — between high (16k) and max (32k)
                case "max" -> 32768;
                default -> 16384;
            };
            builder.thinkingEnabled(budgetTokens);
            builder.maxTokens(Math.max(budgetTokens + 4096,
                    runtimeModel.getMaxTokens() != null ? runtimeModel.getMaxTokens() : 8192));
        } else {
            // Sampling is omitted for every model under the Spring AI 2.1 contract.
            // Anthropic rejects non-positive maxTokens — clamp here so a bad config
            // surfaces as a logged warning instead of an opaque API 400 mid-conversation.
            Integer configuredMax = runtimeModel.getMaxTokens();
            if (configuredMax != null && configuredMax > 0) {
                builder.maxTokens(configuredMax);
            } else {
                if (configuredMax != null) {
                    log.warn("Ignoring non-positive Anthropic maxTokens={} for model {}; falling back to 4096",
                            configuredMax, modelName);
                }
                builder.maxTokens(4096);
            }
        }
        // Prompt cache (system / tools / conversation history) — Spring AI 1.1.4+ first-class.
        builder.cacheOptions(anthropicCacheOptionsFactory.build());

        return builder.build();
    }

    /**
     * Apply 10s connect / 180s read timeouts. The 180s read covers the case
     * where nginx caps the gateway at 60s but a real long thinking response
     * needs more — the upper retry layer takes over once we time out.
     *
     * <p>Package-private + static so {@code ClaudeCodeChatModelBuilder} can
     * apply the same timeouts to its OAuth RestClient without duplicating
     * the snippet.</p>
     */
    static RestClient.Builder applyHttpTimeouts(RestClient.Builder builder) {
        return applyHttpTimeouts(builder, null);
    }

    /**
     * Overload — accepts a per-model read-timeout override
     * (seconds). Null / zero / negative falls back to {@link HttpTimeouts#DEFAULT_READ_TIMEOUT}
     * so unset model configs keep the historical 180s.
     */
    static RestClient.Builder applyHttpTimeouts(RestClient.Builder builder, Integer readTimeoutOverride) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HttpTimeouts.CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.requestFactory(rf);
    }

    /**
     * Streaming counterpart of {@link #applyHttpTimeouts(RestClient.Builder)}.
     *
     * <p><b>Scope caveat (issue #585):</b> {@code setReadTimeout} maps to the
     * JDK HttpClient's request timeout, which only protects up to the
     * <em>response headers</em>. Once the headers arrive the clock stops, so a
     * provider that returns 200 + a first SSE frame and then goes silent is
     * <b>not</b> caught here — the body Flux would hang indefinitely. The
     * body-level gap is closed by a reactor inter-frame idle timeout applied
     * at the streaming chokepoint ({@code NodeStreamingChatHelper}, driven by
     * {@link HttpTimeouts#DEFAULT_STREAM_IDLE_TIMEOUT}).
     * This WebClient timeout still catches the "provider never sends headers"
     * case (connection accepted, no response), so both layers are kept.
     * <p>
     * Uses the same JDK HttpClient + JdkClientHttpConnector path, so the
     * dependency surface doesn't pull in reactor-netty (excluded by this
     * project's pom).
     */
    static WebClient.Builder applyHttpTimeoutsToWebClient(WebClient.Builder builder) {
        return applyHttpTimeoutsToWebClient(builder, null);
    }

    /**
     * Overload — same per-model override semantics as
     * {@link #applyHttpTimeouts(RestClient.Builder, Integer)}.
     */
    static WebClient.Builder applyHttpTimeoutsToWebClient(WebClient.Builder builder, Integer readTimeoutOverride) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HttpTimeouts.CONNECT_TIMEOUT)
                .build();
        JdkClientHttpConnector connector =
                new JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.clientConnector(connector);
    }
}
