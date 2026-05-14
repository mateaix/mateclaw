package vip.mate.llm.embedding;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.text.DashScopeEmbeddingOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.EmbeddingProtocol;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelProviderService;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding 模型工厂
 * <p>
 * 根据 {@link ModelConfigEntity}（model_type='embedding'）构造对应的 {@link EmbeddingModel}：
 * <ul>
 *   <li>DashScope：复用 provider UI 配置的 apiKey/baseUrl，构造 {@link DashScopeEmbeddingModel}</li>
 *   <li>OpenAI 兼容（OpenAI / DeepSeek / 智谱 / Kimi / Moonshot / Ollama 本地等）：构造 {@link OpenAiEmbeddingModel}</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ol>
 *   <li><b>Provider api_key 共用</b>：与 chat 模型共用 {@code mate_model_provider.api_key}，避免重复配置</li>
 *   <li><b>缓存</b>：按 ModelConfigEntity.id 缓存 EmbeddingModel 实例，避免每次查询都重建</li>
 *   <li><b>回落</b>：provider apiKey 未配时，DashScope 走 yml 的 {@code spring.ai.dashscope.api-key}
 *       （兼容老用户），其他 provider 直接报错让用户去 UI 配置</li>
 *   <li><b>独立实现</b>：不引用 {@code AgentGraphBuilder} 的 chat-specific 重写（如 reasoning content patch），
 *       embedding 的请求格式稳定，不需要这些补丁</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingModelFactory {

    private final ModelProviderService providerService;
    private final DashScopeConnectionProperties dashScopeConnectionProperties;

    /** 构造 API 时共享的 retry template（用 Spring AI 默认） */
    private static final RetryTemplate DEFAULT_RETRY = RetryUtils.DEFAULT_RETRY_TEMPLATE;

    /** 按 modelConfig.id 缓存 EmbeddingModel，config 变更时调用 {@link #evict} 清除 */
    private final ConcurrentHashMap<Long, EmbeddingModel> cache = new ConcurrentHashMap<>();

    /**
     * 构造或复用指定配置对应的 EmbeddingModel
     *
     * @throws MateClawException 当 provider 未配置或协议不支持时
     */
    public EmbeddingModel build(ModelConfigEntity modelConfig) {
        if (modelConfig == null) {
            throw new MateClawException("err.embedding.config_null", "Embedding model config is null");
        }
        if (modelConfig.getId() != null) {
            EmbeddingModel cached = cache.get(modelConfig.getId());
            if (cached != null) return cached;
        }

        EmbeddingModel fresh = doBuild(modelConfig);
        if (modelConfig.getId() != null) {
            cache.put(modelConfig.getId(), fresh);
        }
        return fresh;
    }

    /**
     * 清除指定配置的缓存实例（provider api_key 变更 / 模型切换时调用）
     */
    public void evict(Long modelConfigId) {
        if (modelConfigId != null) {
            cache.remove(modelConfigId);
        }
    }

    /** 清空所有缓存（provider 表刷新后调用） */
    public void evictAll() {
        cache.clear();
    }

    // ==================== 内部实现 ====================

    private EmbeddingModel doBuild(ModelConfigEntity modelConfig) {
        ModelProviderEntity provider = providerService.getProviderConfig(modelConfig.getProvider());
        if (provider == null) {
            throw new MateClawException("err.embedding.provider_missing",
                    "Embedding provider '" + modelConfig.getProvider() + "' not found in mate_model_provider");
        }

        EmbeddingProtocol protocol = EmbeddingProtocol.fromProviderId(provider.getProviderId());
        log.info("[EmbeddingFactory] Building embedding model: provider={}, model={}, protocol={}",
                provider.getProviderId(), modelConfig.getModelName(), protocol);

        return switch (protocol) {
            case DASHSCOPE_EMBEDDING -> buildDashScope(provider, modelConfig);
            case OPENAI_EMBEDDING -> buildOpenAi(provider, modelConfig);
        };
    }

    private EmbeddingModel buildDashScope(ModelProviderEntity provider, ModelConfigEntity modelConfig) {
        // API Key 回落链：provider UI → yml
        String apiKey = provider.getApiKey();
        if (!StringUtils.hasText(apiKey) || !providerService.hasUsableApiKey(apiKey)) {
            apiKey = dashScopeConnectionProperties.getApiKey();
        }
        if (!providerService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.embedding.dashscope_key_missing",
                    "DashScope API Key 未配置。请在模型设置中填写 dashscope provider 的 API Key。");
        }

        DashScopeApi.Builder apiBuilder = DashScopeApi.builder().apiKey(apiKey.trim());

        // Base URL 可选
        String baseUrl = provider.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = dashScopeConnectionProperties.getBaseUrl();
        }
        if (StringUtils.hasText(baseUrl)) {
            apiBuilder.baseUrl(baseUrl.trim());
        }

        DashScopeApi api = apiBuilder.build();

        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel(modelConfig.getModelName())
                .build();

        return new DashScopeEmbeddingModel(api, MetadataMode.EMBED, options, DEFAULT_RETRY);
    }

    private EmbeddingModel buildOpenAi(ModelProviderEntity provider, ModelConfigEntity modelConfig) {
        if (!providerService.isProviderConfigured(provider.getProviderId())) {
            throw new MateClawException("err.embedding.provider_not_configured",
                    "Provider '" + provider.getProviderId() + "' 未完成配置（缺少 API Key 或 Base URL）");
        }
        String apiKey = provider.getApiKey();
        if (!providerService.hasUsableApiKey(apiKey)) {
            throw new MateClawException("err.embedding.openai_key_invalid",
                    "Provider API Key 未配置或无效: " + provider.getProviderId());
        }
        String baseUrl = normalizeOpenAiBaseUrl(provider.getBaseUrl());
        if (!StringUtils.hasText(baseUrl)) {
            throw new MateClawException("err.embedding.openai_baseurl_missing",
                    "Provider Base URL 未配置: " + provider.getProviderId());
        }

        // 最简构造：不做 chat-specific 的 header 重写、reasoning patch 等
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey.trim())
                .embeddingsPath("/v1/embeddings")
                .build();

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(modelConfig.getModelName())
                .build();

        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED, options, DEFAULT_RETRY);
    }

    /** 归一化 OpenAI base URL：去掉末尾的 /v1 / 斜杠（避免双 /v1） */
    private String normalizeOpenAiBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) return null;
        String u = baseUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        return u;
    }
}
