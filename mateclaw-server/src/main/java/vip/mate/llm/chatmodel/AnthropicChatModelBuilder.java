package vip.mate.llm.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
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
import java.time.Duration;

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
        AnthropicApi api = buildAnthropicApi(provider, model.getRequestTimeoutSeconds());
        AnthropicChatOptions options = buildAnthropicOptions(model);
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    AnthropicApi buildAnthropicApi(ModelProviderEntity provider) {
        return buildAnthropicApi(provider, null);
    }

    /**
     * Overload — accepts a per-model read-timeout override
     * (seconds). Null falls back to the default 180s.
     */
    AnthropicApi buildAnthropicApi(ModelProviderEntity provider, Integer readTimeoutOverride) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.anthropic_not_configured",
                    "Anthropic Provider 未完成配置，请在模型设置中填写有效的 API Key 和 Base URL");
        }
        String apiKey = provider.getApiKey();
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.anthropic_key_invalid",
                    "Anthropic API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = provider.getBaseUrl();
        RestClient.Builder restClientBuilder = applyHttpTimeouts(
                restClientBuilderProvider.getIfAvailable(RestClient::builder), readTimeoutOverride);
        WebClient.Builder webClientBuilder = applyHttpTimeoutsToWebClient(
                webClientBuilderProvider.getIfAvailable(WebClient::builder), readTimeoutOverride);

        AnthropicApi.Builder builder = AnthropicApi.builder()
                .apiKey(apiKey.trim())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder);
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl.trim());
        }
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

    AnthropicChatOptions buildAnthropicOptions(ModelConfigEntity runtimeModel) {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder();
        String modelName = runtimeModel.getModelName();
        if (StringUtils.hasText(modelName)) {
            builder.model(modelName);
        }
        boolean isClaude47 = isClaude47(modelName);

        // Extended thinking — request-level depth from ThinkingLevelHolder
        String thinkingLevel = ThinkingLevelHolder.get();
        boolean thinkingEnabled = thinkingLevel != null && !"off".equalsIgnoreCase(thinkingLevel);

        if (thinkingEnabled) {
            // Anthropic thinking-mode constraints (pre-4.7): temperature MUST be 1,
            // top_p forbidden, max_tokens must accommodate budget_tokens + buffer.
            // Claude 4.7 forbids temperature/top_p/top_k entirely (any non-null value
            // → HTTP 400) and adds an "xhigh" budget tier between high and max.
            int budgetTokens = switch (thinkingLevel.toLowerCase()) {
                case "low" -> 4096;
                case "medium" -> 8192;
                case "high" -> 16384;
                case "xhigh" -> 24576;  // 4.7 only — between high (16k) and max (32k)
                case "max" -> 32768;
                default -> 16384;
            };
            builder.thinking(AnthropicApi.ThinkingType.ENABLED, budgetTokens);
            builder.maxTokens(Math.max(budgetTokens + 4096,
                    runtimeModel.getMaxTokens() != null ? runtimeModel.getMaxTokens() : 8192));
            // Claude 4.7: omit temperature entirely. Pre-4.7 thinking mode requires
            // temperature=1 (Anthropic-mandated default for thinking).
            if (!isClaude47) {
                builder.temperature(1.0);
            }
        } else {
            // Non-thinking path.
            // - Pre-4.7: Anthropic accepts EITHER temperature OR top_p (not both).
            // - 4.7+: rejects all of temperature/top_p/top_k unless null/default. We
            //   omit them entirely so operators with legacy configs don't 400.
            if (!isClaude47) {
                if (runtimeModel.getTemperature() != null) {
                    builder.temperature(runtimeModel.getTemperature());
                } else if (runtimeModel.getTopP() != null) {
                    builder.topP(runtimeModel.getTopP());
                }
            } else if (runtimeModel.getTemperature() != null || runtimeModel.getTopP() != null) {
                log.debug("Ignoring temperature/top_p for Claude 4.7 model {} (API rejects sampling params)",
                        modelName);
            }
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

        return builder.internalToolExecutionEnabled(false).build();
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
     * (seconds). Null / zero / negative falls back to {@link vip.mate.llm.chatmodel.HttpTimeouts#DEFAULT_READ_TIMEOUT}
     * so unset model configs keep the historical 180s.
     */
    static RestClient.Builder applyHttpTimeouts(RestClient.Builder builder, Integer readTimeoutOverride) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(vip.mate.llm.chatmodel.HttpTimeouts.CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(vip.mate.llm.chatmodel.HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.requestFactory(rf);
    }

    /**
     * Streaming counterpart of {@link #applyHttpTimeouts(RestClient.Builder)}.
     * Without this, Spring AI's AnthropicApi would back its streaming chat
     * call by a default WebClient with neither connect nor read timeout — a
     * stalled provider could hang the agent thread indefinitely while the
     * failover chain idles (no exception = no signal).
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
                .connectTimeout(vip.mate.llm.chatmodel.HttpTimeouts.CONNECT_TIMEOUT)
                .build();
        org.springframework.http.client.reactive.JdkClientHttpConnector connector =
                new org.springframework.http.client.reactive.JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(vip.mate.llm.chatmodel.HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.clientConnector(connector);
    }
}
