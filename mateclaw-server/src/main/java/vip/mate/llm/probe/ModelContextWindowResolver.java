package vip.mate.llm.probe;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the effective context window (max input tokens) for a runtime
 * model, so downstream window budgeting works from the model's real limit
 * instead of the 128k global default.
 *
 * <p>Priority:
 * <ol>
 *   <li>explicit {@code ModelConfigEntity.maxInputTokens} — user configuration
 *       always wins;</li>
 *   <li>a probed value from a {@link LocalContextProbe} (runtime-cached with a
 *       short TTL, never persisted — local servers hot-swap models);</li>
 *   <li>{@code null} — caller falls back to the global default, exactly the
 *       pre-probe behavior.</li>
 * </ol>
 *
 * <p>Reconciliation: when a provider rejects a request for being over the
 * context limit, {@link #noteContextLimitError} parses the limit out of the
 * error text and seeds the same cache, so the very next turn budgets against
 * the true window even where probing is unsupported.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(ContextProbeProperties.class)
public class ModelContextWindowResolver {

    private record CacheEntry(Integer value, long expiresAtMs) {
    }

    private final List<LocalContextProbe> probes;
    private final ContextProbeProperties properties;

    /** Key: providerId + "/" + modelName. Value may hold null (negative cache). */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * @return the effective max input tokens, or {@code null} when neither
     *         explicit config nor probing yields a value (caller keeps its
     *         existing global-default fallback).
     */
    public Integer resolveMaxInputTokens(ModelProviderEntity provider, ModelConfigEntity model) {
        if (model == null) {
            return null;
        }
        if (model.getMaxInputTokens() != null && model.getMaxInputTokens() > 0) {
            return model.getMaxInputTokens();
        }
        if (!properties.isEnabled()) {
            return null;
        }
        String key = cacheKey(provider != null ? provider.getProviderId() : null, model.getModelName());
        CacheEntry cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.value();
        }
        Integer probed = null;
        for (LocalContextProbe probe : probes) {
            try {
                if (!probe.supports(provider, model)) {
                    continue;
                }
                probed = probe.probeContextLength(provider, model).orElse(null);
                if (probed != null) {
                    break;
                }
            } catch (Exception e) {
                log.debug("[ContextProbe] probe {} threw for {}: {}",
                        probe.getClass().getSimpleName(), key, e.getMessage());
            }
        }
        cache.put(key, new CacheEntry(probed, now + ttlMs()));
        if (probed != null) {
            log.info("[ContextProbe] 探测到模型 {} 的上下文窗口为 {} tokens(未配置 maxInputTokens,窗口预算将使用探测值)",
                    key, probed);
        }
        return probed;
    }

    /**
     * Feed a "prompt too long" rejection back into the cache. The parsed limit
     * only takes effect for models without explicit configuration, because
     * {@link #resolveMaxInputTokens} checks explicit config first.
     */
    public void noteContextLimitError(String providerId, String modelName, String errorMessage) {
        if (!properties.isEnabled() || modelName == null || modelName.isBlank()) {
            return;
        }
        OptionalInt parsed = ContextLimitErrorParser.extractLimit(errorMessage);
        if (parsed.isEmpty()) {
            return;
        }
        String key = cacheKey(providerId, modelName);
        int value = parsed.getAsInt();
        cache.put(key, new CacheEntry(value, System.currentTimeMillis() + ttlMs()));
        log.info("[ContextProbe] 从上下文超限报错中解析到模型 {} 的窗口为 {} tokens,已记入运行期缓存", key, value);
    }

    /** Test hook: drop all cached probe results. */
    void clearCache() {
        cache.clear();
    }

    private long ttlMs() {
        return Math.max(1, properties.getCacheTtlSeconds()) * 1000L;
    }

    private static String cacheKey(String providerId, String modelName) {
        return (providerId == null ? "" : providerId) + "/" + (modelName == null ? "" : modelName);
    }
}
