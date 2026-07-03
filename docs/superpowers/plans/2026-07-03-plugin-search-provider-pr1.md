# 插件化搜索 Provider（PR-1：SDK + 桥接 + Registry）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 第三方以独立 jar 实现 `PluginSearchProvider` 并丢进插件目录，即可为 `web_search` 工具新增搜索源，mateclaw-server 源码零改动。

**Architecture:** 在 `mateclaw-plugin-api` 新增 SEARCH 插件类型与自包含 SDK 接口；server 侧用 `PluginSearchBridge` 把插件接口适配成核心 `SearchProvider`，`SearchProviderRegistry` 从 immutable 改为"Spring bean 基底 + 插件区合并视图"；生命周期（注册/disable 反注册/加载失败 rollback）与现有 TOOL/CHANNEL/MEMORY/PROVIDER 四类完全对称。

**Tech Stack:** Java 21 / Spring Boot 3.5 / Maven 多模块 reactor（`mateclaw-plugin-api` → `mateclaw-server` → samples）/ JUnit 5 + Mockito。

**上游 issue:** https://github.com/mateaix/mateclaw/issues/477（已获认可后动工）
**设计文档:** `docs/superpowers/specs/2026-07-03-plugin-search-provider-design.md`

**背景速览（给零上下文的执行者）：**
- 核心接口 `vip.mate.tool.search.SearchProvider`（server 模块）：`id()/label()/requiresCredential()/autoDetectOrder()/isAvailable(SystemSettingsDTO)/search(SearchQuery, SystemSettingsDTO)`。内置 4 个实现（serper/tavily/searxng/duckduckgo）是 `@Component`。
- `SearchProviderRegistry`（`mateclaw-server/src/main/java/vip/mate/tool/search/SearchProviderRegistry.java`）构造器注入 `List<SearchProvider>`，`resolve(config)` 三分支：显式配置 → 按 `autoDetectOrder` 找有 credential 的 → keyless 兜底。
- 插件系统：`PluginManager`（`@RequiredArgsConstructor`，字段即依赖）从文件系统加载 jar（`URLClassLoader`），`PluginContextImpl` 是插件看到的平台 API，`LoadedPlugin` 记录插件注册了什么以便 disable 时反注册。
- 构建命令均从仓库根执行；server 单测在 `mateclaw-server/` 下执行。

---

### Task 1: 建分支并提交设计文档

**Files:**
- 已存在（未提交）: `docs/superpowers/specs/2026-07-03-plugin-search-provider-design.md`
- 本文件: `docs/superpowers/plans/2026-07-03-plugin-search-provider-pr1.md`

- [ ] **Step 1: 从 dev 建分支**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git fetch upstream dev 2>/dev/null || git fetch origin dev
git checkout -b feat/plugin-search-provider $(git rev-parse --verify upstream/dev 2>/dev/null || git rev-parse origin/dev)
```

预期：新分支 `feat/plugin-search-provider`，基于最新 dev。

- [ ] **Step 2: 提交 spec 与 plan**

```bash
git add docs/superpowers/
git commit -m "docs: add plugin search provider design spec and plan (#477)"
```

---

### Task 2: SDK — PluginType.SEARCH + search 包（纯新增，无行为变化）

**Files:**
- Modify: `mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/PluginType.java`
- Create: `mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/search/PluginSearchQuery.java`
- Create: `mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/search/PluginSearchResult.java`
- Create: `mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/search/PluginSearchProvider.java`

注意：**本任务不改 `PluginContext`**（接口加方法会导致 server 模块的 `PluginContextImpl` 编译失败；那一步放在 Task 5 与实现同 commit，保证每个 commit 可编译）。

- [ ] **Step 1: PluginType 加枚举值**

`PluginType.java` 的 `MEMORY` 之后追加：

```java
    /** Register new memory providers */
    MEMORY,

    /** Register new web-search providers for the web_search tool */
    SEARCH
```

（`PluginManifest.validate()` 用 `PluginType.valueOf(type.toUpperCase())` 校验，枚举加值后 manifest `"type": "search"` 自动合法，无需改 validate。）

- [ ] **Step 2: 新建 PluginSearchQuery**

```java
package vip.mate.plugin.api.search;

/**
 * Search query passed from the platform to a plugin search provider.
 * <p>
 * Self-contained SDK type — must not depend on any mateclaw-server class,
 * because plugin JARs are compiled only against mateclaw-plugin-api.
 *
 * @param query     search keywords (never null/blank)
 * @param freshness time-range filter: day / week / month / year (nullable)
 * @param language  language preference, e.g. zh-CN / en (nullable)
 * @param count     max results 1-10, already clamped by the platform (never null)
 *
 * @author MateClaw Team
 */
public record PluginSearchQuery(
        String query,
        String freshness,
        String language,
        Integer count
) {
}
```

- [ ] **Step 3: 新建 PluginSearchResult**

```java
package vip.mate.plugin.api.search;

/**
 * A single search result returned by a plugin search provider.
 * <p>
 * Self-contained SDK type — mirrors the platform's internal SearchResult
 * (title/url/snippet/source/date) without depending on server classes.
 *
 * @param title   result title
 * @param url     result link
 * @param snippet short excerpt
 * @param source  source domain, e.g. "reuters.com" (nullable)
 * @param date    published date as raw string (nullable)
 *
 * @author MateClaw Team
 */
public record PluginSearchResult(
        String title,
        String url,
        String snippet,
        String source,
        String date
) {
}
```

- [ ] **Step 4: 新建 PluginSearchProvider**

```java
package vip.mate.plugin.api.search;

import java.util.List;

/**
 * SPI for plugin-provided web-search providers.
 * <p>
 * Implementations are registered via {@code PluginContext#registerSearchProvider}
 * and appear in the platform's search provider chain alongside the built-in
 * providers (serper / tavily / searxng / duckduckgo).
 * <p>
 * Configuration (API keys, base URLs, ...) is NOT passed in — plugins read their
 * own config declared in {@code mateclaw-plugin.json} via
 * {@code PluginContext#getConfig(String, Class)}.
 *
 * @author MateClaw Team
 */
public interface PluginSearchProvider {

    /** Globally unique provider id, e.g. "my-search". Must not clash with built-in ids. */
    String id();

    /** Human-readable display name. */
    String label();

    /** Whether this provider needs a credential (affects auto-detect priority). */
    default boolean requiresCredential() {
        return true;
    }

    /**
     * Auto-detect ordering (ascending). Built-in providers occupy 50-400;
     * plugin providers default to 500 (after built-ins) but may override.
     */
    default int autoDetectOrder() {
        return 500;
    }

    /**
     * Whether the provider is currently usable — typically: required config present.
     * Called on every provider resolution; keep it cheap (no network I/O).
     */
    boolean isAvailable();

    /**
     * Execute the search.
     *
     * @param query the query (never null)
     * @return results; empty list if nothing found. Must not return null.
     *         Throw on failure — the platform falls back to the next provider.
     */
    List<PluginSearchResult> search(PluginSearchQuery query);
}
```

- [ ] **Step 5: 编译 plugin-api 并安装到本地仓库**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
mvn -q -pl mateclaw-plugin-api install -DskipTests
```

预期：BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add mateclaw-plugin-api/
git commit -m "feat(plugin-api): add SEARCH plugin type and PluginSearchProvider SPI (#477)"
```

---

### Task 3: SearchProviderRegistry 可变化（TDD）

**Files:**
- Test: `mateclaw-server/src/test/java/vip/mate/tool/search/SearchProviderRegistryPluginTest.java`（新建，目录也新建）
- Modify: `mateclaw-server/src/main/java/vip/mate/tool/search/SearchProviderRegistry.java`

- [ ] **Step 1: 写失败测试**

```java
package vip.mate.tool.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.system.model.SystemSettingsDTO;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plugin-provider mutability of {@link SearchProviderRegistry} (issue #477):
 * plugin JARs register/unregister providers at runtime; the registry must merge
 * them with the Spring-injected built-ins and reject id conflicts.
 */
class SearchProviderRegistryPluginTest {

    /** Minimal stub standing in for both built-in and plugin-bridged providers. */
    private static SearchProvider stub(String id, int order, boolean credentialed, boolean available) {
        return new SearchProvider() {
            @Override public String id() { return id; }
            @Override public String label() { return id; }
            @Override public boolean requiresCredential() { return credentialed; }
            @Override public int autoDetectOrder() { return order; }
            @Override public boolean isAvailable(SystemSettingsDTO config) { return available; }
            @Override public List<SearchResult> search(String query, SystemSettingsDTO config) { return List.of(); }
        };
    }

    private static SearchProviderRegistry registryWithBuiltins() {
        // Mirrors the real built-in landscape: one credentialed, one keyless.
        return new SearchProviderRegistry(List.of(
                stub("serper", 300, true, false),      // credentialed but NOT configured
                stub("duckduckgo", 100, false, true)   // keyless, available
        ));
    }

    @Test
    @DisplayName("registered plugin provider shows up in allSorted, ordered by autoDetectOrder")
    void pluginProviderAppearsInMergedSortedView() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);

        registry.registerPluginProvider(plugin);

        List<SearchProvider> all = registry.allSorted();
        assertEquals(3, all.size());
        assertEquals("duckduckgo", all.get(0).id()); // order 100
        assertEquals("serper", all.get(1).id());     // order 300
        assertSame(plugin, all.get(2));              // order 500
    }

    @Test
    @DisplayName("getById finds plugin providers")
    void getByIdFindsPluginProvider() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);
        registry.registerPluginProvider(plugin);

        assertSame(plugin, registry.getById("my-search"));
    }

    @Test
    @DisplayName("plugin id clashing with a built-in id is rejected")
    void builtinIdConflictRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("serper", 500, true, true)));
    }

    @Test
    @DisplayName("plugin id clashing with an already-registered plugin id is rejected")
    void pluginIdConflictRejected() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerPluginProvider(stub("my-search", 501, true, true)));
    }

    @Test
    @DisplayName("resolve honours an explicitly configured plugin provider")
    void resolvePicksConfiguredPluginProvider() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);
        registry.registerPluginProvider(plugin);

        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSearchProvider("my-search");

        SearchProviderRegistry.ResolvedProvider resolved = registry.resolve(config);
        assertSame(plugin, resolved.provider());
        assertEquals("configured", resolved.source());
    }

    @Test
    @DisplayName("resolve auto-detects an available credentialed plugin provider")
    void resolveAutoDetectsPluginProvider() {
        SearchProviderRegistry registry = registryWithBuiltins();
        SearchProvider plugin = stub("my-search", 500, true, true);
        registry.registerPluginProvider(plugin);

        // No explicit provider configured; serper (credentialed) is unavailable,
        // so auto-detect must reach the plugin provider before keyless fallback.
        SearchProviderRegistry.ResolvedProvider resolved = registry.resolve(new SystemSettingsDTO());
        assertSame(plugin, resolved.provider());
        assertEquals("auto-detect", resolved.source());
    }

    @Test
    @DisplayName("after unregister, an explicitly configured plugin id falls back to auto-detect")
    void unregisteredConfiguredProviderFallsBackToAutoDetect() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));
        registry.unregisterPluginProvider("my-search");

        assertNull(registry.getById("my-search"));

        SystemSettingsDTO config = new SystemSettingsDTO();
        config.setSearchProvider("my-search");
        SearchProviderRegistry.ResolvedProvider resolved = registry.resolve(config);
        // Plugin gone; keyless duckduckgo is the only available provider left.
        assertEquals("duckduckgo", resolved.provider().id());
        assertEquals("keyless-fallback", resolved.source());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=SearchProviderRegistryPluginTest
```

预期：编译失败，`registerPluginProvider`/`unregisterPluginProvider` 方法不存在。

- [ ] **Step 3: 修改 SearchProviderRegistry**

对 `SearchProviderRegistry.java` 做如下修改（保留现有 javadoc 与日志，只列出变化）：

新增 import：

```java
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
```

新增字段（`providerMap` 之后）：

```java
    /** 插件注册的 provider（运行时可变），与 Spring 注入的内置 provider 合并成完整视图 */
    private final ConcurrentHashMap<String, SearchProvider> pluginProviders = new ConcurrentHashMap<>();
```

新增方法（`getById` 之前）：

```java
    /**
     * 注册一个插件提供的 provider（issue #477）。
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
```

`getById` 改为查合并视图：

```java
    /** 按 ID 获取指定 provider（内置优先，其次插件注册区） */
    public SearchProvider getById(String id) {
        SearchProvider builtin = providerMap.get(id);
        return builtin != null ? builtin : pluginProviders.get(id);
    }
```

`allSorted` 改为合并视图（provider 总数 <10，每次读时排序无性能顾虑）：

```java
    /** 获取按 autoDetectOrder 排序的全部 provider（内置 + 插件） */
    public List<SearchProvider> allSorted() {
        if (pluginProviders.isEmpty()) {
            return sortedProviders;
        }
        List<SearchProvider> merged = new ArrayList<>(sortedProviders);
        merged.addAll(pluginProviders.values());
        merged.sort(Comparator.comparingInt(SearchProvider::autoDetectOrder));
        return merged;
    }
```

`resolve` 内部两处改为走合并视图：第 1 分支 `providerMap.get(configuredId)` → `getById(configuredId)`；第 2 分支 `for (SearchProvider p : sortedProviders)` → `for (SearchProvider p : allSorted())`。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=SearchProviderRegistryPluginTest
```

预期：7 个测试全 PASS。

- [ ] **Step 5: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-server/src/main/java/vip/mate/tool/search/SearchProviderRegistry.java \
        mateclaw-server/src/test/java/vip/mate/tool/search/SearchProviderRegistryPluginTest.java
git commit -m "feat(search): make SearchProviderRegistry accept runtime plugin providers (#477)"
```

---

### Task 4: PluginSearchBridge（TDD）

**Files:**
- Test: `mateclaw-server/src/test/java/vip/mate/plugin/bridge/PluginSearchBridgeTest.java`（新建，目录也新建）
- Create: `mateclaw-server/src/main/java/vip/mate/plugin/bridge/PluginSearchBridge.java`

- [ ] **Step 1: 写失败测试**

```java
package vip.mate.plugin.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.search.SearchQuery;
import vip.mate.tool.search.SearchResult;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PluginSearchBridge} adapts the self-contained plugin SPI
 * ({@code PluginSearchProvider}) to the platform's {@code SearchProvider}
 * without leaking server types into plugin land.
 */
class PluginSearchBridgeTest {

    @Test
    @DisplayName("query fields pass through and results are converted with the plugin's providerId")
    void convertsQueryAndResults() {
        AtomicReference<PluginSearchQuery> received = new AtomicReference<>();
        PluginSearchProvider plugin = new PluginSearchProvider() {
            @Override public String id() { return "my-search"; }
            @Override public String label() { return "My Search"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) {
                received.set(query);
                return List.of(new PluginSearchResult(
                        "T1", "https://example.com/a", "snippet-1", "example.com", "2026-07-01"));
            }
        };

        PluginSearchBridge bridge = new PluginSearchBridge(plugin);
        List<SearchResult> results = bridge.search(
                new SearchQuery("kw", "week", "zh-CN", 3), new SystemSettingsDTO());

        assertEquals("kw", received.get().query());
        assertEquals("week", received.get().freshness());
        assertEquals("zh-CN", received.get().language());
        assertEquals(3, received.get().count());

        assertEquals(1, results.size());
        SearchResult r = results.get(0);
        assertEquals("T1", r.getTitle());
        assertEquals("https://example.com/a", r.getUrl());
        assertEquals("snippet-1", r.getSnippet());
        assertEquals("example.com", r.getSource());
        assertEquals("2026-07-01", r.getDate());
        assertEquals("my-search", r.getProviderId());
    }

    @Test
    @DisplayName("count is clamped via SearchQuery.resolvedCount before reaching the plugin")
    void countIsClamped() {
        AtomicReference<PluginSearchQuery> received = new AtomicReference<>();
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> {
            received.set(q);
            return List.of();
        }));

        bridge.search(new SearchQuery("kw", null, null, 99), new SystemSettingsDTO());
        assertEquals(10, received.get().count()); // MAX_COUNT

        bridge.search(new SearchQuery("kw", null, null, null), new SystemSettingsDTO());
        assertEquals(5, received.get().count()); // DEFAULT_COUNT
    }

    @Test
    @DisplayName("delegates id/label/order/credential and maps isAvailable() ignoring the DTO")
    void delegatesMetadata() {
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> List.of()));
        assertEquals("stub-search", bridge.id());
        assertEquals("Stub Search", bridge.label());
        assertTrue(bridge.requiresCredential());
        assertEquals(500, bridge.autoDetectOrder());
        assertTrue(bridge.isAvailable(new SystemSettingsDTO()));
    }

    @Test
    @DisplayName("a null result list from a sloppy plugin is normalised to empty")
    void nullResultListNormalised() {
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> null));
        List<SearchResult> results = bridge.search(SearchQuery.of("kw"), new SystemSettingsDTO());
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("plugin exceptions propagate so WebSearchService's fallback chain can react")
    void exceptionsPropagate() {
        PluginSearchBridge bridge = new PluginSearchBridge(stub(q -> {
            throw new IllegalStateException("plugin boom");
        }));
        assertThrows(IllegalStateException.class,
                () -> bridge.search(SearchQuery.of("kw"), new SystemSettingsDTO()));
    }

    // ---- helpers ----

    private interface SearchFn {
        List<PluginSearchResult> apply(PluginSearchQuery q);
    }

    private static PluginSearchProvider stub(SearchFn fn) {
        return new PluginSearchProvider() {
            @Override public String id() { return "stub-search"; }
            @Override public String label() { return "Stub Search"; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) {
                return fn.apply(query);
            }
        };
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=PluginSearchBridgeTest
```

预期：编译失败，`PluginSearchBridge` 不存在。

- [ ] **Step 3: 实现 PluginSearchBridge**

```java
package vip.mate.plugin.bridge;

import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;
import vip.mate.system.model.SystemSettingsDTO;
import vip.mate.tool.search.SearchProvider;
import vip.mate.tool.search.SearchQuery;
import vip.mate.tool.search.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge that wraps a plugin's {@link PluginSearchProvider} into the platform's
 * internal {@link SearchProvider} interface (issue #477).
 * <p>
 * The platform-side {@link SystemSettingsDTO} is intentionally ignored — plugin
 * providers read their own config via {@code PluginContext#getConfig}, keeping
 * the SDK free of server types.
 *
 * @author MateClaw Team
 */
public class PluginSearchBridge implements SearchProvider {

    private final PluginSearchProvider delegate;

    public PluginSearchBridge(PluginSearchProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String label() {
        return delegate.label();
    }

    @Override
    public boolean requiresCredential() {
        return delegate.requiresCredential();
    }

    @Override
    public int autoDetectOrder() {
        return delegate.autoDetectOrder();
    }

    @Override
    public boolean isAvailable(SystemSettingsDTO config) {
        return delegate.isAvailable();
    }

    @Override
    public List<SearchResult> search(String query, SystemSettingsDTO config) {
        return search(SearchQuery.of(query), config);
    }

    @Override
    public List<SearchResult> search(SearchQuery searchQuery, SystemSettingsDTO config) {
        PluginSearchQuery pluginQuery = new PluginSearchQuery(
                searchQuery.query(),
                searchQuery.freshness(),
                searchQuery.language(),
                searchQuery.resolvedCount()
        );
        List<PluginSearchResult> pluginResults = delegate.search(pluginQuery);
        if (pluginResults == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>(pluginResults.size());
        for (PluginSearchResult r : pluginResults) {
            results.add(SearchResult.builder()
                    .title(r.title())
                    .url(r.url())
                    .snippet(r.snippet())
                    .source(r.source())
                    .date(r.date())
                    .providerId(delegate.id())
                    .build());
        }
        return results;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=PluginSearchBridgeTest
```

预期：5 个测试全 PASS。

- [ ] **Step 5: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-server/src/main/java/vip/mate/plugin/bridge/PluginSearchBridge.java \
        mateclaw-server/src/test/java/vip/mate/plugin/bridge/PluginSearchBridgeTest.java
git commit -m "feat(plugin): bridge PluginSearchProvider to the core SearchProvider chain (#477)"
```

---

### Task 5: 注册入口 + 生命周期（PluginContext / PluginContextImpl / LoadedPlugin / PluginManager / PluginInfo）

**Files:**
- Modify: `mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/PluginContext.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/plugin/PluginContextImpl.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/plugin/LoadedPlugin.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/plugin/PluginManager.java`（字段 + 构造点 + rollback + disable + listPlugins）
- Modify: `mateclaw-server/src/main/java/vip/mate/plugin/model/PluginInfo.java`
- Test: `mateclaw-server/src/test/java/vip/mate/plugin/PluginContextImplSearchTest.java`（新建）

接口加方法会让 `PluginContextImpl` 编译失败，因此本任务的接口与实现**必须同一 commit**。

- [ ] **Step 1: 写失败测试**

```java
package vip.mate.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelManager;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.plugin.api.PluginException;
import vip.mate.plugin.api.PluginManifest;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.search.SearchProviderRegistry;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PluginContextImpl#registerSearchProvider: wraps the plugin SPI in a bridge,
 * registers it into SearchProviderRegistry, and records the id on LoadedPlugin
 * so disable/rollback can unregister it (issue #477).
 */
class PluginContextImplSearchTest {

    private SearchProviderRegistry registry;
    private PluginContextImpl context;
    private LoadedPlugin loadedPlugin;

    @BeforeEach
    void setUp() {
        registry = new SearchProviderRegistry(List.of());

        PluginManifest manifest = new PluginManifest();
        manifest.setName("test-plugin");
        manifest.setVersion("1.0.0");
        manifest.setType("search");
        manifest.setEntrypoint("x.Y");

        MateClawPlugin plugin = new MateClawPlugin() {
            @Override public void onLoad(PluginContext ctx) { }
            @Override public void onEnable() { }
            @Override public void onDisable() { }
        };
        loadedPlugin = new LoadedPlugin(manifest, plugin,
                new URLClassLoader(new URL[0], getClass().getClassLoader()));

        context = new PluginContextImpl(
                loadedPlugin, manifest,
                mock(ToolRegistry.class), mock(ChannelManager.class),
                mock(MemoryManager.class), mock(ModelProviderService.class),
                registry,
                null);
    }

    private static PluginSearchProvider provider(String id) {
        return new PluginSearchProvider() {
            @Override public String id() { return id; }
            @Override public String label() { return id; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) {
                return List.of();
            }
        };
    }

    @Test
    @DisplayName("registers into the registry and records the id on LoadedPlugin")
    void registersAndRecords() {
        context.registerSearchProvider(provider("my-search"));

        assertNotNull(registry.getById("my-search"));
        assertEquals(List.of("my-search"), loadedPlugin.getRegisteredSearchProviders());
    }

    @Test
    @DisplayName("id conflict surfaces as PluginException and is not recorded")
    void conflictBecomesPluginException() {
        context.registerSearchProvider(provider("my-search"));

        assertThrows(PluginException.class,
                () -> context.registerSearchProvider(provider("my-search")));
        assertEquals(1, loadedPlugin.getRegisteredSearchProviders().size());
    }

    @Test
    @DisplayName("blank id is rejected with PluginException")
    void blankIdRejected() {
        assertThrows(PluginException.class,
                () -> context.registerSearchProvider(provider("  ")));
        assertTrue(loadedPlugin.getRegisteredSearchProviders().isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=PluginContextImplSearchTest
```

预期：编译失败（`registerSearchProvider` / `getRegisteredSearchProviders` / 7 参构造器不存在）。

- [ ] **Step 3: PluginContext 接口加方法**

`PluginContext.java` 新增 import `vip.mate.plugin.api.search.PluginSearchProvider;`，在 `registerMemoryProvider` 之后加：

```java
    /**
     * Register a web-search provider that joins the platform's search provider
     * chain used by the {@code web_search} tool.
     * <p>
     * The provider id must be globally unique — registration fails with a
     * {@link PluginException} if it clashes with a built-in provider
     * (serper / tavily / searxng / duckduckgo) or another plugin's provider.
     *
     * @param provider the search provider
     * @throws PluginException if the id is blank or already taken
     */
    void registerSearchProvider(PluginSearchProvider provider);
```

- [ ] **Step 4: LoadedPlugin 加记录字段**

`LoadedPlugin.java` 在 `registeredChannels` 之后加：

```java
    /** Search provider ids registered by this plugin */
    private final List<String> registeredSearchProviders = new ArrayList<>();
```

- [ ] **Step 5: PluginContextImpl 实现**

新增 import：

```java
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.bridge.PluginSearchBridge;
import vip.mate.tool.search.SearchProviderRegistry;
```

字段区加 `private final SearchProviderRegistry searchProviderRegistry;`，构造器在 `modelProviderService` 参数后加 `SearchProviderRegistry searchProviderRegistry` 并赋值（保持 `configJson` 为最后一个参数）。

`registerMemoryProvider` 之后加实现：

```java
    @Override
    public void registerSearchProvider(PluginSearchProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new PluginException("Search provider id must not be blank");
        }
        try {
            searchProviderRegistry.registerPluginProvider(new PluginSearchBridge(provider));
        } catch (IllegalArgumentException e) {
            throw new PluginException(e.getMessage());
        }
        loadedPlugin.getRegisteredSearchProviders().add(provider.id());
    }
```

- [ ] **Step 6: PluginManager 注入 registry + 生命周期反注册**

新增 import `vip.mate.tool.search.SearchProviderRegistry;`；字段区（`modelProviderService` 之后）加：

```java
    private final SearchProviderRegistry searchProviderRegistry;
```

（`@RequiredArgsConstructor` 自动进构造器。）

`new PluginContextImpl(...)` 构造点（约 L211）在 `modelProviderService` 后传入 `searchProviderRegistry`。

`rollbackRegistrations`（约 L251）末尾加：

```java
        for (String searchId : loaded.getRegisteredSearchProviders()) {
            try { searchProviderRegistry.unregisterPluginProvider(searchId); } catch (Exception e) { /* best effort */ }
        }
```

`disablePlugin`（约 L269）在 memory/provider 反注册段之后、`loaded.setEnabled(false)` 之前加：

```java
        for (String searchId : loaded.getRegisteredSearchProviders()) {
            searchProviderRegistry.unregisterPluginProvider(searchId);
        }
        int searchRemoved = loaded.getRegisteredSearchProviders().size();
```

并把结尾的 log.info 扩展一个占位：

```java
        log.info("Plugin disabled: {} (tools={}, channels={}, provider={}, memory={}, search={})",
                name, toolsRemoved, channelsRemoved,
                providerRemoved != null ? providerRemoved : "none",
                memoryRemoved != null ? memoryRemoved : "none",
                searchRemoved);
```

`listPlugins()` 内存分支 builder（约 L355）加 `.registeredSearchProviders(List.copyOf(loaded.getRegisteredSearchProviders()))`；DB-only 分支（约 L377）加 `.registeredSearchProviders(List.of())`。

- [ ] **Step 7: PluginInfo 加字段**

`PluginInfo.java` 在 `registeredMemoryProvider` 之后加：

```java
    /** Search provider ids registered by this plugin */
    private List<String> registeredSearchProviders;
```

- [ ] **Step 8: 全链编译 + 跑测试确认通过**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
mvn -q -pl mateclaw-plugin-api install -DskipTests
cd mateclaw-server
mvn test -Dtest='PluginContextImplSearchTest,SearchProviderRegistryPluginTest,PluginSearchBridgeTest'
```

预期：全 PASS。

- [ ] **Step 9: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-plugin-api/src/main/java/vip/mate/plugin/api/PluginContext.java \
        mateclaw-server/src/main/java/vip/mate/plugin/ \
        mateclaw-server/src/test/java/vip/mate/plugin/PluginContextImplSearchTest.java
git commit -m "feat(plugin): registerSearchProvider lifecycle — register, disable, rollback (#477)"
```

---

### Task 6: 参考实现 — mateclaw-plugin-search-sample 模块

**Files:**
- Modify: `pom.xml`（根，`<modules>` 加一行）
- Create: `mateclaw-plugin-search-sample/pom.xml`
- Create: `mateclaw-plugin-search-sample/src/main/resources/mateclaw-plugin.json`
- Create: `mateclaw-plugin-search-sample/src/main/java/vip/mate/plugin/sample/search/SimpleSearchPlugin.java`

设计说明：spec §3.6 说"sample 模块增加参考实现"，但一个插件 jar 只有一个 manifest（一个 type/entrypoint），往现有 `mateclaw-plugin-sample`（type=tool 的 HelloPlugin）里塞 search 会破坏它的演示语义，因此新建独立 sample 模块。实现刻意最小：调一个可配 `baseUrl` 的 JSON 搜索端点（约定响应 `{"results":[{"title","url","snippet"}]}`），JSON 解析用 Jackson（provided，平台父 ClassLoader 提供），HTTP 用 JDK `java.net.http`，零额外依赖。

- [ ] **Step 1: 根 pom 加 module**

`pom.xml` 的 `<modules>` 中 `mateclaw-plugin-sample` 之后加：

```xml
        <module>mateclaw-plugin-search-sample</module>
```

- [ ] **Step 2: 模块 pom**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>vip.mate</groupId>
        <artifactId>mateclaw</artifactId>
        <version>${revision}</version>
        <relativePath>../pom.xml</relativePath>
    </parent>

    <artifactId>mateclaw-plugin-search-sample</artifactId>
    <packaging>jar</packaging>

    <name>MateClaw Search Provider Sample Plugin</name>
    <description>Sample plugin registering a custom web-search provider via the MateClaw Plugin SDK</description>

    <dependencies>
        <!-- MateClaw Plugin API -->
        <dependency>
            <groupId>vip.mate</groupId>
            <artifactId>mateclaw-plugin-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring AI (provided by the platform) — PluginContext method signatures
             reference ToolCallback/ChatModel, so it must be resolvable at compile time -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-model</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Jackson (provided by the platform parent classloader) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- SLF4J (provided by the platform) -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: manifest（含 config schema，驱动 PR-2 的配置表单）**

`src/main/resources/mateclaw-plugin.json`:

```json
{
  "name": "mateclaw-plugin-search-demo",
  "version": "1.0.0",
  "type": "search",
  "displayName": "Demo Search Provider",
  "description": "Registers a custom web-search provider backed by a configurable JSON search endpoint.",
  "entrypoint": "vip.mate.plugin.sample.search.SimpleSearchPlugin",
  "minPlatformVersion": "1.1.0",
  "author": "MateClaw Team",
  "config": {
    "baseUrl": {
      "type": "string",
      "required": true,
      "secret": false,
      "description": "Search endpoint returning {\"results\":[{\"title\",\"url\",\"snippet\"}]}"
    },
    "apiKey": {
      "type": "string",
      "required": false,
      "secret": true,
      "description": "Optional bearer token sent as Authorization header"
    }
  }
}
```

- [ ] **Step 4: 插件实现**

```java
package vip.mate.plugin.sample.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.search.PluginSearchProvider;
import vip.mate.plugin.api.search.PluginSearchQuery;
import vip.mate.plugin.api.search.PluginSearchResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Sample plugin demonstrating {@code PluginType.SEARCH}: registers a search
 * provider that queries a configurable JSON endpoint. Expected response shape:
 * {@code {"results":[{"title":"...","url":"...","snippet":"..."}]}}
 *
 * @author MateClaw Team
 */
public class SimpleSearchPlugin implements MateClawPlugin {

    private Logger log;

    @Override
    public void onLoad(PluginContext context) {
        this.log = context.getLogger();
        context.registerSearchProvider(new DemoSearchProvider(context));
        log.info("SimpleSearchPlugin loaded, search provider registered");
    }

    @Override
    public void onEnable() {
        if (log != null) log.info("SimpleSearchPlugin enabled");
    }

    @Override
    public void onDisable() {
        if (log != null) log.info("SimpleSearchPlugin disabled");
    }

    static class DemoSearchProvider implements PluginSearchProvider {

        private static final Duration TIMEOUT = Duration.ofSeconds(15);

        private final PluginContext context;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        private final ObjectMapper objectMapper = new ObjectMapper();

        DemoSearchProvider(PluginContext context) {
            this.context = context;
        }

        @Override
        public String id() {
            return "demo-search";
        }

        @Override
        public String label() {
            return "Demo Search";
        }

        @Override
        public boolean isAvailable() {
            String baseUrl = context.getConfig("baseUrl", String.class);
            return baseUrl != null && !baseUrl.isBlank();
        }

        @Override
        public List<PluginSearchResult> search(PluginSearchQuery query) {
            String baseUrl = context.getConfig("baseUrl", String.class);
            String apiKey = context.getConfig("apiKey", String.class);

            String url = baseUrl + (baseUrl.contains("?") ? "&" : "?")
                    + "q=" + URLEncoder.encode(query.query(), StandardCharsets.UTF_8)
                    + "&count=" + query.count();

            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                req.header("Authorization", "Bearer " + apiKey);
            }

            try {
                HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("Search endpoint returned HTTP " + resp.statusCode());
                }
                return parse(resp.body());
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Search request failed: " + e.getMessage(), e);
            }
        }

        private List<PluginSearchResult> parse(String body) throws Exception {
            List<PluginSearchResult> results = new ArrayList<>();
            JsonNode items = objectMapper.readTree(body).path("results");
            for (JsonNode item : items) {
                results.add(new PluginSearchResult(
                        item.path("title").asText(null),
                        item.path("url").asText(null),
                        item.path("snippet").asText(null),
                        null,
                        null));
            }
            return results;
        }
    }
}
```

- [ ] **Step 5: 全量构建（reactor 三模块 + 新 sample）**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
mvn -q -pl mateclaw-plugin-search-sample -am package -DskipTests
ls mateclaw-plugin-search-sample/target/*.jar
```

预期：BUILD SUCCESS，产出 `mateclaw-plugin-search-sample-1.7.0-SNAPSHOT.jar`。

- [ ] **Step 6: Commit**

```bash
git add pom.xml mateclaw-plugin-search-sample/
git commit -m "feat(plugin): add search provider sample plugin module (#477)"
```

---

### Task 7: 回归 + 手工端到端验证

- [ ] **Step 1: server 全量测试回归**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test
```

预期：全绿（重点确认 `WebSearchTool`/`WebSearchService`/registry 相关既有测试无回归）。

- [ ] **Step 2: 手工端到端（jar 落盘加载）**

```bash
mkdir -p ~/.mateclaw/plugins
cp /Users/connor/workspace/ai-lab/mateclaw/mateclaw-plugin-search-sample/target/mateclaw-plugin-search-sample-*.jar ~/.mateclaw/plugins/
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn spring-boot:run
```

启动日志验证三点：
1. `Plugin loaded: mateclaw-plugin-search-demo v1.0.0 (type=search, ...)`
2. `插件搜索提供商已注册: demo-search (order=500)`
3. 无 id 冲突/rollback 报错。

再验证 disable 路径：登录（admin/admin123）→ 插件页关掉该插件 → 日志出现 `插件搜索提供商已反注册: demo-search` 与 `Plugin disabled: ... search=1`。

验证完清理：

```bash
rm ~/.mateclaw/plugins/mateclaw-plugin-search-sample-*.jar
```

- [ ] **Step 3: 若有问题修复后补 commit；全部通过则进入 Task 8**

---

### Task 8: 推分支 + 开 PR

- [ ] **Step 1: 推到 origin（ncw1992120/mateclaw）**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git push -u origin feat/plugin-search-provider
```

- [ ] **Step 2: 开 PR 到 mateaix:dev**

```bash
gh pr create --repo mateaix/mateclaw --base dev \
  --title "feat(plugin): 插件化搜索 Provider — PluginType.SEARCH + PluginSearchProvider SPI (#477)" \
  --body "$(cat <<'EOF'
Closes 部分 #477（PR-1：SDK + 桥接 + registry；PR-2 的 catalog 接口与设置页重构另行提交）。

## 改动
- `mateclaw-plugin-api`: `PluginType.SEARCH` + `vip.mate.plugin.api.search` 包（`PluginSearchProvider`/`PluginSearchQuery`/`PluginSearchResult`，自包含、不依赖 server 类）+ `PluginContext.registerSearchProvider()`
- `mateclaw-server`: `PluginSearchBridge`（适配核心 `SearchProvider`）；`SearchProviderRegistry` 支持运行时注册/反注册插件 provider（合并视图，id 冲突拒绝）；`PluginManager` disable/rollback 反注册与现有四类对称；`PluginInfo` 暴露 `registeredSearchProviders`
- 新模块 `mateclaw-plugin-search-sample`：type=search 的最小参考实现（可配 baseUrl/apiKey 的 JSON 端点）

## 测试
- `SearchProviderRegistryPluginTest`（7）：合并排序 / getById / 内置与插件 id 冲突 / resolve 三分支含插件项 / 反注册回退
- `PluginSearchBridgeTest`（5）：query/result 转换、count 钳制、null 归一、异常透传
- `PluginContextImplSearchTest`（3）：注册记录、冲突转 PluginException、空 id 拒绝
- 手工端到端：sample jar 落盘 → 启动加载注册 → disable 反注册，日志均符合预期

无破坏性改动：接口新增方法/枚举值对存量插件透明；`GET/PUT /api/v1/settings` 未动。
EOF
)"
```

- [ ] **Step 2 后**：PR 链接贴回 issue #477。

---

## 范围外（本计划不做）

- PR-2：`GET /api/v1/settings/search-providers` catalog 接口、搜索设置页分组折叠、`Plugins.vue` schema 配置表单 —— PR-1 合并后单独出计划。
- 面向插件作者的开发文档（仓库内暂无 plugin dev docs 先例，是否新增由上游在 PR 评审中定夺）。
