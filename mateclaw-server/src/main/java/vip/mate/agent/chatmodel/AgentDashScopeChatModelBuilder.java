package vip.mate.agent.chatmodel;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeApiSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.chatmodel.ChatModelBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProtocol;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Strategy implementation for {@link ModelProtocol#DASHSCOPE_NATIVE}.
 *
 * <p>Owns all DashScope-specific construction logic (api + options) plus the
 * fallback-chain helpers for resolving API key / Base URL when the provider
 * row is incomplete. PR-0b moved this code out of {@code AgentGraphBuilder}
 * so the agent package no longer carries any DashScope schema knowledge.</p>
 *
 * <p>DashScopeChatModel is injected via ObjectProvider so that the builder
 * degrades gracefully when DashScope auto-configuration is disabled or the
 * dependency is absent, rather than failing the entire application context.</p>
 */
@Slf4j
@Component
public class AgentDashScopeChatModelBuilder implements ChatModelBuilder {

    private final ObjectProvider<DashScopeChatModel> dashScopeChatModelProvider;
    private final DashScopeConnectionProperties dashScopeConnectionProperties;
    private final ModelProviderService modelProviderService;

    public AgentDashScopeChatModelBuilder(ObjectProvider<DashScopeChatModel> dashScopeChatModelProvider,
                                          DashScopeConnectionProperties dashScopeConnectionProperties,
                                          ModelProviderService modelProviderService) {
        this.dashScopeChatModelProvider = dashScopeChatModelProvider;
        this.dashScopeConnectionProperties = dashScopeConnectionProperties;
        this.modelProviderService = modelProviderService;
    }

    @Override
    public ModelProtocol supportedProtocol() {
        return ModelProtocol.DASHSCOPE_NATIVE;
    }

    @Override
    public ChatModel build(ModelConfigEntity model, ModelProviderEntity provider, RetryTemplate retry) {
        DashScopeChatModel defaultModel = dashScopeChatModelProvider.getIfAvailable();
        if (defaultModel == null) {
            throw new MateClawException("err.agent.dashscope_unavailable",
                    "DashScope 自动配置未激活（可能缺少依赖或被排除），无法构建 DashScope 模型");
        }
        DashScopeApi api = buildDashScopeApi(provider);
        DashScopeChatOptions options = buildDashScopeOptions(model, provider);
        return defaultModel.mutate()
                .dashScopeApi(api)
                .defaultOptions(options)
                .build();
    }

    /**
     * Bailian's built-in web search is only accepted by a subset of models;
     * sending {@code enable_search} to a model that doesn't support it returns
     * 400 InvalidParameter and the failover layer then evicts the entire
     * provider as MODEL_NOT_FOUND. Per the public docs, only Qwen-Plus,
     * Qwen-Max, Qwen-Turbo and the Qwen3-Max series accept the parameter.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Explicit {@code enableSearch} in the model row (per-model toggle)</li>
     *   <li>Explicit {@code enableSearch} in provider kwargs (admin-level toggle)</li>
     *   <li>Default: enabled only when the model name matches a known-supporting
     *       prefix; disabled for everything else (coder / thinking / DeepSeek /
     *       Long / Vision)</li>
     * </ol>
     *
     * <p>Public so {@code AgentGraphBuilder.build()} can surface the
     * "built-in search active" log once per agent.</p>
     */
    public boolean isBuiltinSearchEnabled(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        if (runtimeModel != null && runtimeModel.getEnableSearch() != null) {
            return Boolean.TRUE.equals(runtimeModel.getEnableSearch());
        }
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);
        Object kwargsSearch = kwargs.get("enableSearch");
        if (kwargsSearch != null) {
            return Boolean.TRUE.equals(kwargsSearch);
        }
        return modelSupportsBuiltinSearch(runtimeModel);
    }

    /**
     * Model id prefixes that the Bailian text-generation endpoint documents as
     * accepting {@code enable_search}. Anything else (qwen-coder-*, qwen-long,
     * qwen3-*-thinking-*, deepseek-*, qwen*-vl-*) returns 400 InvalidParameter
     * when the parameter is sent.
     */
    private static final java.util.List<String> BUILTIN_SEARCH_SUPPORTED_PREFIXES = java.util.List.of(
            "qwen-plus",
            "qwen-max",
            "qwen-turbo",
            "qwen3-max",
            "qwen3.5-flash",
            "qwen3.6-flash"
    );

    private static boolean modelSupportsBuiltinSearch(ModelConfigEntity model) {
        if (model == null) return false;
        String name = model.getModelName();
        if (!StringUtils.hasText(name)) return false;
        String lower = name.trim().toLowerCase();
        for (String prefix : BUILTIN_SEARCH_SUPPORTED_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    DashScopeChatOptions buildDashScopeOptions(ModelConfigEntity runtimeModel, ModelProviderEntity provider) {
        DashScopeChatOptions.DashScopeChatOptionsBuilder builder = DashScopeChatOptions.builder();
        Map<String, Object> kwargs = modelProviderService.readProviderGenerateKwargs(provider);

        if (StringUtils.hasText(runtimeModel.getModelName())) {
            builder.withModel(runtimeModel.getModelName());
        }
        if (runtimeModel.getTemperature() != null) {
            builder.withTemperature(runtimeModel.getTemperature());
        }
        if (runtimeModel.getMaxTokens() != null) {
            builder.withMaxToken(runtimeModel.getMaxTokens());
        }
        if (runtimeModel.getTopP() != null) {
            builder.withTopP(runtimeModel.getTopP());
        }
        if (isBuiltinSearchEnabled(runtimeModel, provider)) {
            builder.withEnableSearch(true);
            String strategy = runtimeModel.getSearchStrategy();
            if (!StringUtils.hasText(strategy)) {
                strategy = (String) kwargs.get("searchStrategy");
            }
            if (StringUtils.hasText(strategy)) {
                builder.withSearchOptions(DashScopeApiSpec.SearchOptions.builder()
                        .searchStrategy(strategy)
                        .enableSource(true)
                        .enableCitation(true)
                        .build());
            }
        }
        return builder.build();
    }

    DashScopeApi buildDashScopeApi(ModelProviderEntity provider) {
        DashScopeApi.Builder builder = DashScopeApi.builder();

        // API Key fallback chain: provider UI config → env / application.yml → default bean reflection
        String apiKey = provider != null ? provider.getApiKey() : null;
        if (!StringUtils.hasText(apiKey) || !modelProviderService.hasUsableApiKey(apiKey)) {
            apiKey = dashScopeConnectionProperties.getApiKey();
        }
        if (!StringUtils.hasText(apiKey) || !modelProviderService.hasUsableApiKey(apiKey)) {
            apiKey = readApiKeyFromDefaultChatModel();
        }
        if (!modelProviderService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.agent.dashscope_key_missing",
                    "DashScope API Key 未配置，请在「设置 → 模型 → 添加供应商」中为 dashscope 填写 API Key");
        }
        builder.apiKey(apiKey.trim());

        // Base URL fallback chain — same priority as API Key
        String baseUrl = provider != null ? provider.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = dashScopeConnectionProperties.getBaseUrl();
        }
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = readBaseUrlFromDefaultChatModel();
        }
        String normalizedBaseUrl = normalizeDashScopeBaseUrl(baseUrl);
        if (StringUtils.hasText(normalizedBaseUrl)) {
            builder.baseUrl(normalizedBaseUrl);
        }
        return builder.build();
    }

    /**
     * Strip the OpenAI compatible-mode path off any user-supplied URL
     * (common when migrating from compat-mode), trim trailing slash, and
     * return null when the result is the SDK default — letting Spring AI's
     * built-in default win avoids path-concat surprises.
     */
    private String normalizeDashScopeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        String normalized = baseUrl.trim();
        int compatibleIndex = normalized.indexOf("/compatible-mode/");
        if (compatibleIndex >= 0) {
            normalized = normalized.substring(0, compatibleIndex);
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if ("https://dashscope.aliyuncs.com".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    // ============================================================
    // Reflection helpers — read whatever the auto-configured default
    // DashScopeChatModel was built with, as a final fallback when no
    // explicit credentials reach us.
    // ============================================================

    private String readApiKeyFromDefaultChatModel() {
        try {
            DashScopeApi api = readDashScopeApiFromDefaultChatModel();
            if (api == null) return null;
            Field apiKeyField = DashScopeApi.class.getDeclaredField("apiKey");
            apiKeyField.setAccessible(true);
            Object apiKey = apiKeyField.get(api);
            if (apiKey instanceof org.springframework.ai.model.ApiKey key) {
                return key.getValue();
            }
        } catch (Exception e) {
            log.warn("Failed to read API key from default DashScopeChatModel: {}", e.getMessage());
        }
        return null;
    }

    private String readBaseUrlFromDefaultChatModel() {
        try {
            DashScopeApi api = readDashScopeApiFromDefaultChatModel();
            if (api == null) return null;
            Field baseUrlField = DashScopeApi.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            Object baseUrl = baseUrlField.get(api);
            return baseUrl instanceof String value ? value : null;
        } catch (Exception e) {
            log.warn("Failed to read baseUrl from default DashScopeChatModel: {}", e.getMessage());
            return null;
        }
    }

    private DashScopeApi readDashScopeApiFromDefaultChatModel() throws NoSuchFieldException, IllegalAccessException {
        DashScopeChatModel defaultModel = dashScopeChatModelProvider.getIfAvailable();
        if (defaultModel == null) {
            return null;
        }
        Field apiField = DashScopeChatModel.class.getDeclaredField("dashscopeApi");
        apiField.setAccessible(true);
        Object api = apiField.get(defaultModel);
        return api instanceof DashScopeApi dashScopeApi ? dashScopeApi : null;
    }
}
