package vip.mate.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.ApiKey;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strategy implementation of {@link ChatModelBuilder} for
 * {@link ModelProtocol#OPENAI_COMPATIBLE}.
 *
 * <p>Owns the full OpenAI-compatible construction path: the {@link OpenAiApi}
 * client (HTTP timeouts, header overrides, completions-path resolution), the
 * {@link OpenAiChatOptions} (temperature / max tokens / reasoning effort / web
 * search), and the outbound request-rewrite pipeline delegated to
 * {@link OpenAiRequestRewriter}. DeepSeek V4 reasoning models are wrapped with
 * {@link DeepSeekV4ThinkingDecorator}.
 *
 * <p>Depends only on infrastructure beans, so the {@code llm} package builds a
 * {@link ChatModel} without any dependency on the agent graph layer.
 */
@Slf4j
@Component
public class OpenAiCompatibleChatModelBuilder implements ChatModelBuilder {

    private final ModelProviderService modelProviderService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    public OpenAiCompatibleChatModelBuilder(
            ModelProviderService modelProviderService,
            ObjectMapper objectMapper,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.modelProviderService = modelProviderService;
        this.objectMapper = objectMapper;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
        this.observationRegistryProvider = observationRegistryProvider;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        // Pass model.requestTimeoutSeconds so providers / models with
        // extended-thinking p99s don't false-positive on the default read timeout.
        OpenAiApi api = buildOpenAiApi(provider, model.getRequestTimeoutSeconds());
        OpenAiChatOptions options = buildOpenAiOptions(model, provider);
        ChatModel raw = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .retryTemplate(retry)
                .observationRegistry(observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();

        // DeepSeek V4 (flash / pro) extends OpenAI's wire format with `thinking: {type}` and a
        // strict reasoning_content replay contract. Spring AI's OpenAiChatOptions can't express
        // those directly — wrap with a per-request payload patcher.
        if (ModelFamily.detect(model.getModelName()) == ModelFamily.DEEPSEEK_V4_REASONING) {
            return new DeepSeekV4ThinkingDecorator(raw);
        }
        return raw;
    }

    // ==================== chat options ====================

    OpenAiChatOptions buildOpenAiOptions(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder();
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String modelName = runtimeModel.getModelName();
        ModelFamily family = ModelFamily.detect(modelName);

        if (StringUtils.hasText(modelName)) {
            builder.model(modelName);
        }

        // temperature: some model families force 1.0
        Double temperature = resolveOpenAiTemperature(modelName, runtimeModel.getTemperature(), kwargs, family);
        if (temperature != null) {
            builder.temperature(temperature);
        }

        // max_tokens / max_completion_tokens: routed by model family
        if (family.suppressMaxTokens()) {
            // OPENAI_REASONING family: max_tokens forbidden, use max_completion_tokens.
            // fallback priority: kwargs.maxCompletionTokens > kwargs.maxTokens > config.maxTokens
            Integer kwargsMaxTokens = ProviderGenerateKwargs.resolveIntegerOption(
                    "maxTokens", runtimeModel.getMaxTokens(), kwargs);
            Integer maxCompletionTokens = ProviderGenerateKwargs.resolveIntegerOption(
                    "maxCompletionTokens", kwargsMaxTokens, kwargs);
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
            log.debug("ModelFamily {} suppressed max_tokens, using max_completion_tokens={} for model {}",
                    family, maxCompletionTokens, modelName);
        } else {
            // Other model families: use max_tokens normally
            Integer maxTokens = ProviderGenerateKwargs.resolveIntegerOption(
                    "maxTokens", runtimeModel.getMaxTokens(), kwargs);
            if (maxTokens != null) {
                builder.maxTokens(maxTokens);
            }
            // Still allow maxCompletionTokens to be set explicitly via generateKwargs
            Integer maxCompletionTokens = ProviderGenerateKwargs.resolveIntegerOption(
                    "maxCompletionTokens", null, kwargs);
            if (maxCompletionTokens != null) {
                builder.maxCompletionTokens(maxCompletionTokens);
            }
        }

        // top_p: forbidden for some model families
        Double topP = resolveOpenAiTopP(modelName, runtimeModel.getTopP(), kwargs, family);
        if (topP != null) {
            builder.topP(topP);
        }

        // reasoning_effort: injected only for supporting model families
        String reasoningEffort = ReasoningEffortResolver.resolveReasoningEffort(modelName, kwargs, family);
        if (StringUtils.hasText(reasoningEffort)) {
            builder.reasoningEffort(reasoningEffort);
        }

        // built-in search: model-level field wins, provider generateKwargs as fallback
        boolean searchEnabled = Boolean.TRUE.equals(runtimeModel.getEnableSearch())
                || Boolean.TRUE.equals(kwargs.get("enableSearch"));
        if (searchEnabled) {
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) kwargs.get("searchStrategy");
            }
            OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize contextSize;
            try {
                contextSize = StringUtils.hasText(strategy)
                        ? OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.valueOf(strategy.toUpperCase())
                        : OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.MEDIUM;
            } catch (IllegalArgumentException e) {
                contextSize = OpenAiApi.ChatCompletionRequest.WebSearchOptions.SearchContextSize.MEDIUM;
            }
            builder.webSearchOptions(new OpenAiApi.ChatCompletionRequest.WebSearchOptions(contextSize, null));
        }

        OpenAiChatOptions options = builder.build();
        options.setInternalToolExecutionEnabled(false);
        // Do not set parallelToolCalls — setting it to false makes OpenAI return 400 when
        // there are no tools: "parallel_tool_calls is only allowed when 'tools' are specified".
        // Leaving it null keeps Spring AI from serializing the field; each node controls it
        // when tools are present.
        options.setStreamUsage(true);
        return options;
    }

    private Double resolveOpenAiTemperature(String modelName, Double configuredTemperature,
                                            Map<String, Object> kwargs, ModelFamily family) {
        Double overriddenTemperature = ProviderGenerateKwargs.resolveDoubleOption(
                "temperature", configuredTemperature, kwargs);
        if (family.fixedTemperatureOne()) {
            if (overriddenTemperature == null || Double.compare(overriddenTemperature, 1.0d) != 0) {
                log.info("ModelFamily {} forced temperature=1.0 for model {}", family, modelName);
            }
            return 1.0d;
        }
        return overriddenTemperature;
    }

    private Double resolveOpenAiTopP(String modelName, Double configuredTopP,
                                     Map<String, Object> kwargs, ModelFamily family) {
        if (family.suppressTopP()) {
            return null;
        }
        return ProviderGenerateKwargs.resolveDoubleOption("topP", configuredTopP, kwargs);
    }

    // ==================== OpenAI API client ====================

    /**
     * Build an {@link OpenAiApi} for the provider. Accepts a per-model
     * read-timeout override (seconds), threaded into both the sync RestClient and
     * the streaming WebClient. Null falls back to the default 180s.
     */
    OpenAiApi buildOpenAiApi(ModelProviderEntity provider, Integer readTimeoutOverride) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.agent.provider_not_configured",
                    "Provider 未完成配置，请在模型设置中填写有效的 API Key 和 Base URL");
        }
        String apiKey = provider.getApiKey();
        // Honor the provider's requireApiKey flag instead of hard-failing on every empty key.
        // Local + key-free providers (Ollama, LM Studio, MLX, llama.cpp, OpenCode) declare
        // requireApiKey=false; for them an empty / placeholder key means "no Authorization
        // header" — Spring AI's NoopApiKey expresses that.
        boolean keyRequired = !Boolean.FALSE.equals(provider.getRequireApiKey());
        if (keyRequired && !modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.provider_apikey_invalid",
                    "Provider API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.agent.provider_baseurl_missing",
                    "Provider Base URL 未配置: " + provider.getProviderId());
        }
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        MultiValueMap<String, String> headers = buildOpenAiHeaders(kwargs);
        String completionsPath = resolveOpenAiCompletionsPath(baseUrl, kwargs);
        RestClient.Builder restClientBuilder = applyHttpTimeouts(
                restClientBuilderProvider.getIfAvailable(RestClient::builder), readTimeoutOverride);
        WebClient.Builder webClientBuilder = applyHttpTimeoutsToWebClient(
                webClientBuilderProvider.getIfAvailable(WebClient::builder), readTimeoutOverride);

        // Spring AI's OpenAiApi constructor sets User-Agent to "spring-ai" first, then addAll's
        // our headers, so a custom User-Agent is appended rather than replaced. For providers
        // that must masquerade as a specific client (e.g. kimi-code), force-override headers
        // via a RestClient/WebClient interceptor before the request goes out.
        Map<String, String> overrideHeaders = extractOverrideHeaders(kwargs);
        if (!overrideHeaders.isEmpty()) {
            restClientBuilder = restClientBuilder.requestInterceptor((request, body, execution) -> {
                HttpHeaders reqHeaders = request.getHeaders();
                overrideHeaders.forEach(reqHeaders::set);
                return execution.execute(request, body);
            });
            webClientBuilder = webClientBuilder.filter((request, next) -> {
                org.springframework.web.reactive.function.client.ClientRequest modified =
                        org.springframework.web.reactive.function.client.ClientRequest.from(request)
                                .headers(h -> overrideHeaders.forEach(h::set))
                                .build();
                return next.exchange(modified);
            });
        }

        boolean kimiSearchEnabled = isKimiProvider(provider)
                && Boolean.TRUE.equals(kwargs.get("enableSearch"));

        ApiKey apiKeyImpl = (keyRequired && StringUtils.hasText(apiKey))
                ? new SimpleApiKey(apiKey.trim())
                : new NoopApiKey();
        return new OpenAiApi(
                baseUrl,
                apiKeyImpl,
                headers,
                completionsPath,
                "/v1/embeddings",
                restClientBuilder,
                webClientBuilder,
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER) {
            @Override
            public org.springframework.http.ResponseEntity<OpenAiApi.ChatCompletion> chatCompletionEntity(
                    OpenAiApi.ChatCompletionRequest chatRequest,
                    MultiValueMap<String, String> additionalHttpHeader) {
                chatRequest = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(chatRequest, provider);
                chatRequest = OpenAiRequestRewriter.patchReasoningContent(chatRequest, provider);
                chatRequest = OpenAiRequestRewriter.stripReasoningEffortIfIncompatible(chatRequest);
                chatRequest = OpenAiRequestRewriter.stripAutoToolChoice(chatRequest);
                chatRequest = OpenAiRequestRewriter.patchVideoMediaContent(chatRequest);
                if (kimiSearchEnabled) {
                    chatRequest = OpenAiRequestRewriter.injectKimiWebSearch(chatRequest);
                }
                logOpenAiRequest(provider, chatRequest);
                try {
                    return super.chatCompletionEntity(chatRequest, additionalHttpHeader);
                } catch (WebClientResponseException e) {
                    logOpenAiError(provider, e);
                    throw e;
                }
            }

            @Override
            public Flux<OpenAiApi.ChatCompletionChunk> chatCompletionStream(
                    OpenAiApi.ChatCompletionRequest chatRequest,
                    MultiValueMap<String, String> additionalHttpHeader) {
                chatRequest = OpenAiRequestRewriter.sanitizeReasoningEffortForProvider(chatRequest, provider);
                chatRequest = OpenAiRequestRewriter.patchReasoningContent(chatRequest, provider);
                chatRequest = OpenAiRequestRewriter.stripReasoningEffortIfIncompatible(chatRequest);
                chatRequest = OpenAiRequestRewriter.stripAutoToolChoice(chatRequest);
                chatRequest = OpenAiRequestRewriter.patchVideoMediaContent(chatRequest);
                if (kimiSearchEnabled) {
                    chatRequest = OpenAiRequestRewriter.injectKimiWebSearch(chatRequest);
                }
                logOpenAiRequest(provider, chatRequest);
                return super.chatCompletionStream(chatRequest, additionalHttpHeader)
                        .doOnError(error -> {
                            if (error instanceof WebClientResponseException e) {
                                logOpenAiError(provider, e);
                            }
                        });
            }
        };
    }

    /**
     * Whether the provider is one of Kimi's first-party providers. Public so the
     * agent graph builder can surface a "built-in search active" log line.
     */
    public static boolean isKimiProvider(ModelProviderEntity provider) {
        if (provider == null) return false;
        String id = provider.getProviderId();
        return "kimi-cn".equals(id) || "kimi-intl".equals(id);
    }

    // ==================== URL / headers ====================

    private String normalizeOpenAiBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return null;
        }
        String normalized = baseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private MultiValueMap<String, String> buildOpenAiHeaders(Map<String, Object> kwargs) {
        LinkedMultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.add("User-Agent", "MateClaw/1.0");
        Object headerObject = kwargs.get("headers");
        if (headerObject instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.set(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return headers;
    }

    /**
     * Extract headers that must be force-overridden, read from
     * {@code generateKwargs.headers}. Used by a RestClient/WebClient interceptor
     * to bypass Spring AI's default User-Agent.
     */
    private Map<String, String> extractOverrideHeaders(Map<String, Object> kwargs) {
        Map<String, String> result = new HashMap<>();
        Object headerObject = kwargs.get("headers");
        if (headerObject instanceof Map<?, ?> headerMap) {
            headerMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    result.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return result;
    }

    // Trailing "/v{digits}" segment in a base URL — the OpenAI-compatible convention
    // (/v1 OpenAI, /v3 Volcano Ark, /v4 Zhipu). When the baseUrl already carries this
    // segment, the default /v1 prefix on the path must be stripped to avoid building
    // a broken URL like /api/v3/v1/chat/completions.
    private static final Pattern OPENAI_BASE_URL_VERSION_SUFFIX = Pattern.compile(".*/v\\d+$");

    private String resolveOpenAiCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        Object raw = kwargs.get("completionsPath");
        boolean explicit = raw instanceof String value && StringUtils.hasText(value);
        String path = explicit ? ((String) raw).trim() : "/v1/chat/completions";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // An explicit completionsPath is honored as-is. Otherwise, dedupe the /v1
        // prefix when the baseUrl already ends with /v{N} (Volcano Engine Ark /v3,
        // Zhipu /v4, etc.).
        if (!explicit
                && baseUrl != null
                && OPENAI_BASE_URL_VERSION_SUFFIX.matcher(baseUrl).matches()
                && path.startsWith("/v1/")) {
            path = path.substring(3);
        }
        return path;
    }

    // ==================== HTTP timeouts ====================

    /**
     * Configure an explicit timeout on the RestClient used for LLM calls so a
     * socket never hangs forever.
     *
     * <p>Uses {@link JdkClientHttpRequestFactory} (backed by the Java 11+
     * {@link HttpClient}) because it natively supports HTTP/2 / ALPN negotiation
     * and transparently decompresses {@code Content-Encoding: gzip} responses.
     *
     * <p>connectTimeout=10s; readTimeout defaults to 180s (covers an nginx 60s
     * gateway timeout plus headroom for a real long response; the upper retry
     * layer takes over once it times out).
     */
    private RestClient.Builder applyHttpTimeouts(RestClient.Builder builder, Integer readTimeoutOverride) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HttpTimeouts.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.requestFactory(rf);
    }

    /**
     * Apply equivalent timeouts to the WebClient backing OpenAI-compatible
     * STREAMING calls. Without this the streaming path uses a default WebClient
     * with neither connect nor read timeout, so a stalled provider can hang the
     * call indefinitely while the failover chain idles (no exception thrown).
     *
     * <p>Uses {@link org.springframework.http.client.reactive.JdkClientHttpConnector}
     * with the same {@link HttpClient} so the dependency surface stays clean
     * (reactor-netty is not on this project's classpath).
     */
    private WebClient.Builder applyHttpTimeoutsToWebClient(WebClient.Builder builder, Integer readTimeoutOverride) {
        // Pin HTTP/1.1: many self-hosted OpenAI-compatible servers (vLLM, lmstudio,
        // llama.cpp, ollama — all uvicorn/ASGI based) only speak HTTP/1.1 over
        // cleartext and slam the socket on the JDK client's default H2C upgrade
        // probe, surfacing as "header parser received no bytes" with no body sent.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(HttpTimeouts.CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        org.springframework.http.client.reactive.JdkClientHttpConnector connector =
                new org.springframework.http.client.reactive.JdkClientHttpConnector(httpClient);
        connector.setReadTimeout(HttpTimeouts.resolveReadTimeout(readTimeoutOverride));
        return builder.clientConnector(connector);
    }

    // ==================== logging ====================

    private void logOpenAiRequest(ModelProviderEntity provider, OpenAiApi.ChatCompletionRequest chatRequest) {
        try {
            log.info("OpenAI-compatible request: provider={}, body={}",
                    provider.getProviderId(), objectMapper.writeValueAsString(chatRequest));
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAI-compatible request for {}: {}",
                    provider.getProviderId(), e.getMessage());
        }
    }

    private void logOpenAiError(ModelProviderEntity provider, WebClientResponseException e) {
        log.error("OpenAI-compatible error: provider={}, status={}, body={}",
                provider.getProviderId(), e.getStatusCode(), e.getResponseBodyAsString());
    }
}
