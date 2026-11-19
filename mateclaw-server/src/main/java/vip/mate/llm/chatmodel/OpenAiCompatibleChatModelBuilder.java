package vip.mate.llm.chatmodel;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelFamily;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Builds an immutable Spring AI model with final HTTP request policies for compatible providers. */
@Slf4j
@Component
public class OpenAiCompatibleChatModelBuilder implements ChatModelBuilder {

    private final ModelProviderService modelProviderService;
    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    public OpenAiCompatibleChatModelBuilder(
            ModelProviderService modelProviderService,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.modelProviderService = modelProviderService;
        this.observationRegistryProvider = observationRegistryProvider;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        validateProvider(provider);
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        OpenAiChatOptions.Builder configured = buildOpenAiOptions(model, provider).mutate();
        configured.baseUrl(baseUrl).apiKey(StringUtils.hasText(provider.getApiKey()) && modelProviderService.hasUsableApiKey(provider.getApiKey())
                ? provider.getApiKey().trim() : "");
        configured.maxRetries(0).timeout(HttpTimeouts.resolveReadTimeout(model.getRequestTimeoutSeconds()));
        OpenAiChatOptions options = configured.build();
        OpenAiCompatibleHttpInterceptor interceptor = new OpenAiCompatibleHttpInterceptor(provider,
                baseUrl + resolveOpenAiCompletionsPath(baseUrl, kwargs),
                buildOpenAiHeaders(kwargs).toSingleValueMap(),
                isKimiProvider(provider) && Boolean.TRUE.equals(ProviderGenerateKwargs.findOptionValue(kwargs, "enableSearch")),
                HttpTimeouts.resolveReadTimeout(model.getRequestTimeoutSeconds()));
        ObservationRegistry observations = observationRegistryProvider == null ? ObservationRegistry.NOOP
                : observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
        SpringAiOpenAiHttpClient.Builder observed = SpringAiOpenAiHttpClient.builder()
                .observationRegistry(observations).timeout(options.getTimeout()).proxy(options.getProxy());
        boolean authenticated = StringUtils.hasText(options.getApiKey());
        if (!authenticated) observed.interceptor(chain -> chain.proceed(
                chain.request().newBuilder().removeHeader("Authorization").build()));
        observed.interceptor(interceptor);
        PinnedOpenAiHttpClient transport = new PinnedOpenAiHttpClient(observed.build());
        ClientOptions.Builder clientOptions = ClientOptions.builder().httpClient(transport).baseUrl(baseUrl)
           .apiKey(authenticated ? options.getApiKey() : "mateclaw-keyless-placeholder")
           .timeout(options.getTimeout()).maxRetries(0);
        /*
        * fix：https://github.com/mateaix/mateclaw/issues/640
        * Azure unified (/openai/v1) base URLs must not be downgraded by the SDK:
        * with the default AUTO mode openai-java classifies *.openai.azure.com hosts
        * as AZURE_LEGACY (our /v1-stripped baseUrl never ends in /openai/v1), rewrites
        * request paths to /openai/deployments/{model} and appends a default
        * api-version query — both wrong for the v1 surface the operator configured.
        */
        if (isAzureUnifiedBaseUrl(provider.getBaseUrl())) {
            clientOptions.azureUrlPathMode(com.openai.azure.AzureUrlPathMode.UNIFIED);
        }
        OpenAIClientImpl sdk = new OpenAIClientImpl(clientOptions.build());
        ChatModel raw = OpenAiChatModel.builder().options(options).openAiClient(sdk).openAiClientAsync(sdk.async())
                .observationRegistry(observations).build();
        raw = new OpenAiWirePolicyChatModel(raw, retry, transport);
        if (ModelFamily.detect(model.getModelName()) == ModelFamily.DEEPSEEK_V4_REASONING)
            return new DeepSeekV4ThinkingDecorator(raw);
        return VllmThinkingDecorator.supports(provider, options) ? new VllmThinkingDecorator(raw) : raw;
    }

    private void validateProvider(ModelProviderEntity provider) {
        if (provider == null || !modelProviderService.isProviderConfigured(provider.getProviderId()))
            throw new MateClawException("err.agent.provider_not_configured", "Provider is not configured");
        if (!Boolean.FALSE.equals(provider.getRequireApiKey()) && !modelProviderService.hasUsableApiKey(provider.getApiKey()))
            throw new MateClawException("err.agent.provider_apikey_invalid", "Provider API Key is invalid");
        if (!StringUtils.hasText(normalizeOpenAiBaseUrl(provider.getBaseUrl())))
            throw new MateClawException("err.agent.provider_baseurl_missing", "Provider Base URL is missing");
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
                || Boolean.TRUE.equals(ProviderGenerateKwargs.findOptionValue(kwargs, "enableSearch"));
        if (searchEnabled) {
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) ProviderGenerateKwargs.findOptionValue(kwargs, "searchStrategy");
            }
            String contextSize = StringUtils.hasText(strategy) ? strategy.toLowerCase(Locale.ROOT) : "medium";
            if (!Set.of("low", "medium", "high").contains(contextSize)) contextSize = "medium";
            builder.extraBody(Map.of("web_search_options", Map.of("search_context_size", contextSize)));
        }
        builder.streamUsage(true);
        Map<String, Object> extraBody = new LinkedHashMap<>();
        if (builder.build().getExtraBody() != null) extraBody.putAll(builder.build().getExtraBody());
        extraBody.putAll(ProviderGenerateKwargs.collectPassthroughExtraBody(kwargs));
        builder.extraBody(extraBody);
        OpenAiChatOptions options = builder.build();
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
        // Compatible gateways may close idle sockets; keep the configured connection policy.
        headers.add("Connection", "close");
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

    // Trailing "/v{digits}" segment in a base URL — the OpenAI-compatible convention
    // (/v1 OpenAI, /v3 Volcano Ark, /v4 Zhipu). When the baseUrl already carries this
    // segment, the default /v1 prefix on the path must be stripped to avoid building
    // a broken URL like /api/v3/v1/chat/completions.
    private static final Pattern OPENAI_BASE_URL_VERSION_SUFFIX = Pattern.compile(".*/v\\d+$");

    private String resolveOpenAiCompletionsPath(String baseUrl, Map<String, Object> kwargs) {
        Object raw = ProviderGenerateKwargs.findOptionValue(kwargs, "completionsPath");
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

    /**
     * fix：https://github.com/mateaix/mateclaw/issues/640
     * Whether the operator configured an Azure OpenAI resource through the unified
     * {@code /openai/v1} surface (per Microsoft's "switching endpoints" guidance).
     * On that surface no {@code api-version} query parameter may be sent, and the
     * SDK must not rewrite paths into the legacy {@code /openai/deployments/{name}}
     * form — see {@link #build(ModelConfigEntity, ModelProviderEntity, RetryTemplate)}.
     */
    private static boolean isAzureUnifiedBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null) return false;
        String url = rawBaseUrl.trim();
        while (url.endsWith("/") || url.endsWith("?")) {
            url = url.substring(0, url.length() - 1);
        }
        return url.endsWith("/openai/v1")
           && (url.contains("openai.azure.com") || url.contains("cognitiveservices.azure.com"));
    }

    /** Defensive scrub for provider error bodies, which may echo request data. */
    static String redactAndTruncate(String body) {
        String redacted = body
                .replaceAll("(?i)(\\\"(?:api[_-]?key|authorization|token)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")",
                        "$1[REDACTED]$2")
                .replaceAll("(?i)((?:api[_-]?key|authorization|token)\\s*[=:]\\s*)[^,}\\s]+",
                        "$1[REDACTED]")
                .replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+\\-/=]+", "$1[REDACTED]");
        int max = 1024;
        return redacted.length() <= max ? redacted : redacted.substring(0, max) + "…";
    }
}
