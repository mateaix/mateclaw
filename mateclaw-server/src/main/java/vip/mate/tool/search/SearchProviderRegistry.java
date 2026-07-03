package vip.mate.tool.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 搜索提供商注册表 — 收集所有 {@link SearchProvider} 实现，提供优先级排序与自动探测
 *
 * <p>provider 自动探测机制：
 * <ol>
 *   <li>用户显式配置的 primary provider → 直接使用</li>
 *   <li>按 autoDetectOrder 遍历，优先选有 credential 的 provider</li>
 *   <li>如果没有有 credential 的 provider，回退到第一个可用的 keyless provider</li>
 * </ol>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class SearchProviderRegistry {

    private final List<SearchProvider> sortedProviders;
    private final Map<String, SearchProvider> providerMap;

    /** 插件注册的 provider（运行时可变），与 Spring 注入的内置 provider 合并成完整视图 */
    private final ConcurrentHashMap<String, SearchProvider> pluginProviders = new ConcurrentHashMap<>();

    public SearchProviderRegistry(List<SearchProvider> providers) {
        this.sortedProviders = providers.stream()
                .sorted(Comparator.comparingInt(SearchProvider::autoDetectOrder))
                .toList();
        this.providerMap = providers.stream()
                .collect(Collectors.toMap(SearchProvider::id, Function.identity()));
        log.info("注册搜索提供商 {} 个: {}", sortedProviders.size(),
                sortedProviders.stream().map(p -> p.id() + "(order=" + p.autoDetectOrder() + ")").toList());
    }

    /**
     * 注册一个插件提供的 provider。
     *
     * @throws IllegalArgumentException id 为空，或与内置/已注册插件 provider 冲突
     */
    public void registerPluginProvider(SearchProvider provider) {
        String id = provider.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Search provider id must not be blank");
        }
        if (providerMap.containsKey(id)) {
            throw new IllegalArgumentException(
                    "Search provider id conflicts with a built-in provider: " + id);
        }
        if (pluginProviders.putIfAbsent(id, provider) != null) {
            throw new IllegalArgumentException(
                    "Search provider id already registered by another plugin: " + id);
        }
        log.info("插件搜索提供商已注册: {} (order={})", id, provider.autoDetectOrder());
    }

    /** 反注册插件 provider（disable / rollback 路径调用；id 不存在时静默） */
    public void unregisterPluginProvider(String id) {
        if (pluginProviders.remove(id) != null) {
            log.info("插件搜索提供商已反注册: {}", id);
        }
    }

    /** 判断某个 id 是否由插件注册（而非内置 Spring bean） */
    public boolean isPluginProvider(String id) {
        return pluginProviders.containsKey(id);
    }

    /** 按 ID 获取指定 provider（内置优先，其次插件注册区） */
    public SearchProvider getById(String id) {
        SearchProvider builtin = providerMap.get(id);
        return builtin != null ? builtin : pluginProviders.get(id);
    }

    /**
     * 获取按 autoDetectOrder 排序的全部 provider（内置 + 插件）。
     * <p>有插件注册时每次调用重新合并排序——provider 总数 &lt;10，无需缓存。
     */
    public List<SearchProvider> allSorted() {
        if (pluginProviders.isEmpty()) {
            return sortedProviders;
        }
        List<SearchProvider> merged = new ArrayList<>(sortedProviders);
        merged.addAll(pluginProviders.values());
        merged.sort(Comparator.comparingInt(SearchProvider::autoDetectOrder));
        return merged;
    }

    /**
     * 根据当前配置，解析应使用的 provider。
     *
     * <p>解析策略：
     * <ol>
     *   <li>如果用户配置了 primary provider 且该 provider 可用 → 选中</li>
     *   <li>否则按 autoDetectOrder 遍历，跳过 keyless，先找有 credential 的</li>
     *   <li>如果没找到 → 回退到第一个可用的 keyless provider</li>
     * </ol>
     *
     * @return 选中的 provider，或 null（完全无可用 provider）
     */
    public ResolvedProvider resolve(SystemSettingsDTO config) {
        // 1. 用户显式配置的 primary provider
        String configuredId = config.getSearchProvider();
        if (configuredId != null && !configuredId.isBlank()) {
            SearchProvider configured = getById(configuredId);
            if (configured != null && configured.isAvailable(config)) {
                return new ResolvedProvider(configured, "configured");
            }
        }

        // 2. 按优先级遍历，先找有 credential 的
        SearchProvider keylessFallback = null;
        for (SearchProvider p : allSorted()) {
            if (!p.requiresCredential()) {
                // 记住第一个可用的 keyless provider
                if (keylessFallback == null && p.isAvailable(config)) {
                    keylessFallback = p;
                }
                continue;
            }
            if (p.isAvailable(config)) {
                return new ResolvedProvider(p, "auto-detect");
            }
        }

        // 3. 回退到 keyless
        if (keylessFallback != null) {
            return new ResolvedProvider(keylessFallback, "keyless-fallback");
        }

        return null;
    }

    /**
     * 解析结果
     */
    public record ResolvedProvider(SearchProvider provider, String source) {
    }
}
