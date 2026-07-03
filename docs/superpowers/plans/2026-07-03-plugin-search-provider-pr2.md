# 插件化搜索 Provider（PR-2：Catalog 接口 + 设置页重构 + 插件配置表单）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搜索设置页从"9 个配置项拍平在一个列表 + 硬编码 2 个下拉选项"改成"动态 catalog 驱动的分组折叠卡片"，同时补上插件系统缺失的通用配置表单（schema 驱动，服务所有插件类型）。

**Architecture:** 后端新增一个只读 catalog 接口，聚合 `SearchProviderRegistry`（内置+插件 provider 合并视图）与 `PluginManager`（反查某个 provider 属于哪个插件）；前端把这份 catalog 数据渲染成动态下拉 + 折叠卡片，`Plugins.vue` 补一个通用的 schema 驱动配置表单（复用已有的 `configSchema`/`currentConfig`/`updateConfig` 链路，这条链路后端早就绪，只是前端从未接上）。

**Tech Stack:** Java 21 / Spring Boot 3.5 / JUnit 5 + Mockito（后端）；Vue 3 + TypeScript + Vitest（前端组合式函数单测）+ 手工浏览器验证（Vue 组件渲染）。

**分支关系：** 本分支 `feat/plugin-search-provider-pr2` **stack 在 PR-1 分支 `feat/plugin-search-provider`（PR #479，未合并）之上**。开 PR 时 base 仍是 `mateaix:dev`，diff 会暂时包含 PR-1 的全部改动，PR 描述里需注明"待 #479 合并后 diff 自动收窄"，这是本项目已有的 stacked-branch 先例（issue #436 用过同样模式）。
**设计文档：** `docs/superpowers/specs/2026-07-03-plugin-search-provider-design.md` §3.3–3.5
**上游 issue：** https://github.com/mateaix/mateclaw/issues/477

**背景速览（给零上下文的执行者）：**
- `SearchProviderRegistry`（`mateclaw-server/src/main/java/vip/mate/tool/search/SearchProviderRegistry.java`，PR-1 已改）：`allSorted()` 返回内置+插件 provider 合并排序列表；`resolve(SystemSettingsDTO config)` 返回 `ResolvedProvider(provider, source)`，`source` ∈ `"configured"/"auto-detect"/"keyless-fallback"`（无可用 provider 时返回 `null`）。**没有**区分某个 id 是内置还是插件注册的公开方法——本计划 Task 1 补上。
- `PluginManager`（`mateclaw-server/src/main/java/vip/mate/plugin/PluginManager.java`）：`listPlugins()` 返回 `List<PluginInfo>`，每个含 `registeredSearchProviders: List<String>`。**没有**"给一个 search provider id，反查是哪个插件注册的"的方法——本计划 Task 2 补上。
- `SystemSettingService`（`mateclaw-server/src/main/java/vip/mate/system/service/SystemSettingService.java`）：`@RequiredArgsConstructor`，当前只有一个字段 `private final SystemSettingMapper systemSettingMapper`。**加新字段会改变构造器签名**，必须同步改现有测试 `mateclaw-server/src/test/java/vip/mate/system/service/SystemSettingBoolApiTest.java`（目前用 `new SystemSettingService(mapper)` 单参构造）。`getSearchSettings()` 返回明文（含未脱敏 API Key）的 `SystemSettingsDTO`，专供后端内部调用（如 provider 的 `isAvailable(config)` 判断）。
- `SystemSettingController`（`mateclaw-server/src/main/java/vip/mate/system/controller/SystemSettingController.java`）：`@RestController @RequestMapping("/api/v1/settings")`，现有 `GET/PUT /settings` 用 `@RequireWorkspaceRole("admin")`。
- `PluginController`（`mateclaw-server/src/main/java/vip/mate/plugin/controller/PluginController.java`）：`PUT /api/v1/plugins/{name}/config` 已存在，接受 `Map<String, Object>`，`PluginManager.updateConfig()` 已做 schema 白名单+required 校验（不用改后端这块）。
- `PluginInfo`（`mateclaw-server/src/main/java/vip/mate/plugin/model/PluginInfo.java`）：`configSchema: Map<String,Object>`（来自 manifest，key→`{type,required,secret,description}`）、`currentConfig: Map<String,Object>`（`secret` 字段已脱敏）——**这条链路后端完全就绪，前端从未接上**。
- 前端类型：`mateclaw-ui/src/types/index.ts:801` `SystemSettings` 接口；API 客户端：`mateclaw-ui/src/api/index.ts` 的 `settingsApi`（约 L663）、`pluginApi`（约 L1104）；i18n：`mateclaw-ui/src/i18n/locales/{zh-CN,en-US}.ts`，key 路径 `settings.fields.*`/`settings.hints.*`/`settings.searchTitle`/`plugins.*`。
- 前端组合式函数测试先例：`mateclaw-ui/src/composables/__tests__/*.test.ts`（Vitest），命令 `pnpm test`（`mateclaw-ui/package.json` 已配 `vitest run`）。
- 命令均从仓库根或对应子目录执行；后端单测在 `mateclaw-server/` 下用 `mvn test -Dtest=...`；前端在 `mateclaw-ui/` 下用 `pnpm test`。

---

### Task 1: `SearchProviderRegistry.isPluginProvider(id)`（TDD）

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/tool/search/SearchProviderRegistry.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/tool/search/SearchProviderRegistryPluginTest.java`

- [ ] **Step 1: 写失败测试** — 在 `SearchProviderRegistryPluginTest.java` 的 `blankIdRejected` 测试之后加：

```java
    @Test
    @DisplayName("isPluginProvider distinguishes built-in ids from plugin-registered ids")
    void isPluginProviderDistinguishesSource() {
        SearchProviderRegistry registry = registryWithBuiltins();
        registry.registerPluginProvider(stub("my-search", 500, true, true));

        assertTrue(registry.isPluginProvider("my-search"));
        assertFalse(registry.isPluginProvider("serper"));
        assertFalse(registry.isPluginProvider("duckduckgo"));
        assertFalse(registry.isPluginProvider("does-not-exist"));
    }
```

（文件顶部已有 `import static org.junit.jupiter.api.Assertions.assertThrows;` 等，需再加 `assertTrue`/`assertFalse` 到 static import 列表。）

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=SearchProviderRegistryPluginTest
```

预期：编译失败，`isPluginProvider` 方法不存在。

- [ ] **Step 3: 实现** — 在 `SearchProviderRegistry.java` 的 `unregisterPluginProvider` 方法之后加：

```java
    /** 判断某个 id 是否由插件注册（而非内置 Spring bean） */
    public boolean isPluginProvider(String id) {
        return pluginProviders.containsKey(id);
    }
```

- [ ] **Step 4: 跑测试确认通过**（9 个测试全 PASS）

```bash
mvn test -Dtest=SearchProviderRegistryPluginTest
```

- [ ] **Step 5: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-server/src/main/java/vip/mate/tool/search/SearchProviderRegistry.java \
        mateclaw-server/src/test/java/vip/mate/tool/search/SearchProviderRegistryPluginTest.java
git commit -m "feat(search): expose SearchProviderRegistry.isPluginProvider for catalog UI (#477)"
```

---

### Task 2: `PluginManager.getPluginNameForSearchProvider(id)`（TDD）

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/plugin/PluginManager.java`
- Test: `mateclaw-server/src/test/java/vip/mate/plugin/PluginManagerSearchLookupTest.java`（新建）

**背景**：`PluginManager` 是 `@RequiredArgsConstructor @Component`，字段较多（`PluginProperties, PluginMapper, ToolRegistry, ChannelManager, MemoryManager, ModelProviderService, Optional<WorkspaceService>, SearchProviderRegistry`）。新方法只读 `plugins`（`Map<String, LoadedPlugin>` 私有字段），用反射设置测试夹具（照抄 `McpClientManagerSnapshotTest.java` 的 `field()` 反射辅助模式）。

- [ ] **Step 1: 写失败测试**

```java
package vip.mate.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.channel.ChannelManager;
import vip.mate.llm.service.ModelProviderService;
import vip.mate.memory.spi.MemoryManager;
import vip.mate.plugin.api.MateClawPlugin;
import vip.mate.plugin.api.PluginContext;
import vip.mate.plugin.api.PluginManifest;
import vip.mate.plugin.repository.PluginMapper;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.workspace.service.WorkspaceService;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * PluginManager#getPluginNameForSearchProvider: reverse-lookup which loaded
 * plugin registered a given search provider id, used by the settings catalog
 * endpoint to show "managed by plugin X" (issue #477).
 */
class PluginManagerSearchLookupTest {

    private PluginManager manager() {
        // Constructor param order MUST match PluginManager's field declaration order
        // (Lombok @RequiredArgsConstructor): pluginProperties, pluginMapper, toolRegistry,
        // channelManager, memoryManager, modelProviderService, searchProviderRegistry, workspaceService.
        return new PluginManager(
                mock(vip.mate.plugin.PluginProperties.class),
                mock(PluginMapper.class),
                mock(ToolRegistry.class),
                mock(ChannelManager.class),
                mock(MemoryManager.class),
                mock(ModelProviderService.class),
                new SearchProviderRegistry(List.of()),
                Optional.<WorkspaceService>empty());
    }

    private LoadedPlugin loadedPluginWithSearchIds(String name, String... searchIds) {
        PluginManifest manifest = new PluginManifest();
        manifest.setName(name);
        manifest.setVersion("1.0.0");
        manifest.setType("search");
        manifest.setEntrypoint("x.Y");
        MateClawPlugin plugin = new MateClawPlugin() {
            @Override public void onLoad(PluginContext ctx) { }
            @Override public void onEnable() { }
            @Override public void onDisable() { }
        };
        LoadedPlugin loaded = new LoadedPlugin(manifest, plugin,
                new URLClassLoader(new URL[0], getClass().getClassLoader()));
        loaded.getRegisteredSearchProviders().addAll(List.of(searchIds));
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private void seedPlugins(PluginManager manager, LoadedPlugin... loaded) throws Exception {
        Field f = PluginManager.class.getDeclaredField("plugins");
        f.setAccessible(true);
        Map<String, LoadedPlugin> map = (Map<String, LoadedPlugin>) f.get(manager);
        for (LoadedPlugin l : loaded) {
            map.put(l.getManifest().getName(), l);
        }
    }

    @Test
    @DisplayName("finds the plugin name that registered the given search provider id")
    void findsOwningPlugin() throws Exception {
        PluginManager manager = manager();
        seedPlugins(manager, loadedPluginWithSearchIds("plugin-a", "my-search"));

        assertEquals("plugin-a", manager.getPluginNameForSearchProvider("my-search"));
    }

    @Test
    @DisplayName("returns null when no loaded plugin registered that id")
    void returnsNullWhenNotFound() throws Exception {
        PluginManager manager = manager();
        seedPlugins(manager, loadedPluginWithSearchIds("plugin-a", "other-search"));

        assertNull(manager.getPluginNameForSearchProvider("my-search"));
    }

    @Test
    @DisplayName("returns null for a built-in id no plugin ever registered")
    void returnsNullForBuiltinId() throws Exception {
        PluginManager manager = manager();

        assertNull(manager.getPluginNameForSearchProvider("serper"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=PluginManagerSearchLookupTest
```

预期：编译失败，`getPluginNameForSearchProvider` 不存在。

- [ ] **Step 3: 实现** — 在 `PluginManager.java` 的 `disablePlugin` 方法之后（或任意合适的只读查询方法群组处）加：

```java
    /**
     * 反查某个 search provider id 是由哪个已加载插件注册的（issue #477，供设置页 catalog 用）。
     *
     * @return 插件名（manifest 的 name），找不到返回 {@code null}
     */
    public String getPluginNameForSearchProvider(String searchProviderId) {
        return plugins.values().stream()
                .filter(p -> p.getRegisteredSearchProviders().contains(searchProviderId))
                .map(p -> p.getManifest().getName())
                .findFirst()
                .orElse(null);
    }
```

- [ ] **Step 4: 跑测试确认通过**（3 个测试全 PASS）

```bash
mvn test -Dtest=PluginManagerSearchLookupTest
```

- [ ] **Step 5: 跑一次全量 plugin 包测试确认无回归**

```bash
mvn test -Dtest='vip.mate.plugin.**'
```

- [ ] **Step 6: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-server/src/main/java/vip/mate/plugin/PluginManager.java \
        mateclaw-server/src/test/java/vip/mate/plugin/PluginManagerSearchLookupTest.java
git commit -m "feat(plugin): add reverse lookup from search provider id to owning plugin (#477)"
```

---

### Task 3: Catalog 响应 DTO + `SystemSettingService.getSearchProviderCatalog()`（TDD）

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/system/model/SearchProviderCatalogEntry.java`
- Create: `mateclaw-server/src/main/java/vip/mate/system/model/SearchProviderCatalogResponse.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/system/service/SystemSettingService.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/system/service/SystemSettingBoolApiTest.java`（构造器签名变了，必须同步改）
- Test: `mateclaw-server/src/test/java/vip/mate/system/service/SystemSettingServiceCatalogTest.java`（新建）

**关键设计**：`SystemSettingService` 加两个新的 `final` 字段（`SearchProviderRegistry`、`PluginManager`）会通过 `@RequiredArgsConstructor` 自动改变构造器签名，**这会让现有测试 `SystemSettingBoolApiTest`（用 `new SystemSettingService(mapper)` 单参构造）编译失败** —— 必须在同一个 commit 里把它也改成新的多参构造（照抄 PR-1 Task 5 处理 `PluginContextImpl` 8 参构造器的先例）。

- [ ] **Step 1: 写失败测试** — 新建 `SystemSettingServiceCatalogTest.java`：

```java
package vip.mate.system.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.plugin.PluginManager;
import vip.mate.system.model.SearchProviderCatalogResponse;
import vip.mate.system.model.SystemSettingEntity;
import vip.mate.system.repository.SystemSettingMapper;
import vip.mate.tool.search.SearchProvider;
import vip.mate.tool.search.SearchProviderRegistry;
import vip.mate.tool.search.SearchResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SystemSettingService#getSearchProviderCatalog: aggregates SearchProviderRegistry
 * (builtin + plugin providers) with PluginManager (owning-plugin lookup) into the
 * catalog payload the settings UI renders (issue #477).
 */
@ExtendWith(MockitoExtension.class)
class SystemSettingServiceCatalogTest {

    @Mock private SystemSettingMapper mapper;
    @Mock private PluginManager pluginManager;

    private SystemSettingService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new Configuration(), ""),
                SystemSettingEntity.class);
    }

    private static SearchProvider stub(String id, int order, boolean credentialed, boolean available) {
        return new SearchProvider() {
            @Override public String id() { return id; }
            @Override public String label() { return id + "-label"; }
            @Override public boolean requiresCredential() { return credentialed; }
            @Override public int autoDetectOrder() { return order; }
            @Override public boolean isAvailable(vip.mate.system.model.SystemSettingsDTO config) { return available; }
            @Override public List<SearchResult> search(String query, vip.mate.system.model.SystemSettingsDTO config) { return List.of(); }
        };
    }

    @BeforeEach
    void setUp() {
        when(mapper.selectOne(any())).thenReturn(null); // no DB rows -> defaults used by getSearchSettings()
    }

    @Test
    @DisplayName("marks builtin providers as builtin=true with no pluginName")
    void builtinEntry() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(stub("serper", 300, true, false)));
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertEquals(1, catalog.providers().size());
        var entry = catalog.providers().get(0);
        assertEquals("serper", entry.id());
        assertTrue(entry.builtin());
        assertNull(entry.pluginName());
        assertFalse(entry.available()); // not configured
    }

    @Test
    @DisplayName("marks plugin-registered providers as builtin=false with the owning pluginName")
    void pluginEntry() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of());
        registry.registerPluginProvider(stub("my-search", 500, true, true));
        when(pluginManager.getPluginNameForSearchProvider("my-search")).thenReturn("my-plugin");
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        var entry = catalog.providers().get(0);
        assertEquals("my-search", entry.id());
        assertFalse(entry.builtin());
        assertEquals("my-plugin", entry.pluginName());
        assertTrue(entry.available());
    }

    @Test
    @DisplayName("surfaces the resolved provider id and source alongside the catalog")
    void resolvedSurfaced() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(stub("duckduckgo", 100, false, true)));
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertEquals("duckduckgo", catalog.resolvedId());
        assertEquals("keyless-fallback", catalog.resolvedSource());
    }

    @Test
    @DisplayName("resolvedId/resolvedSource are null when no provider is available at all")
    void resolvedNullWhenNothingAvailable() {
        SearchProviderRegistry registry = new SearchProviderRegistry(List.of(stub("serper", 300, true, false)));
        service = new SystemSettingService(mapper, registry, pluginManager);

        SearchProviderCatalogResponse catalog = service.getSearchProviderCatalog();

        assertNull(catalog.resolvedId());
        assertNull(catalog.resolvedSource());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest=SystemSettingServiceCatalogTest
```

预期：编译失败（新构造器、`getSearchProviderCatalog`、两个新 DTO 类均不存在）。

- [ ] **Step 3: 新建 `SearchProviderCatalogEntry`**

```java
package vip.mate.system.model;

/**
 * One row in the search-provider catalog exposed to the settings UI (issue #477).
 *
 * @param id                provider id (matches {@code SearchProvider.id()})
 * @param label             display label
 * @param builtin           {@code true} for the four shipped providers, {@code false} for plugin-registered ones
 * @param requiresCredential whether the provider needs an API key/credential
 * @param available         whether it's currently usable under the active config
 * @param pluginName        owning plugin's manifest name; {@code null} when {@code builtin} is true
 */
public record SearchProviderCatalogEntry(
        String id,
        String label,
        boolean builtin,
        boolean requiresCredential,
        boolean available,
        String pluginName
) {
}
```

- [ ] **Step 4: 新建 `SearchProviderCatalogResponse`**

```java
package vip.mate.system.model;

import java.util.List;

/**
 * Response payload for {@code GET /api/v1/settings/search-providers} (issue #477).
 *
 * @param providers     all registered providers (builtin + plugin), sorted by autoDetectOrder
 * @param resolvedId    the id of the provider that would actually be used right now; {@code null} if none available
 * @param resolvedSource why it was picked: "configured" / "auto-detect" / "keyless-fallback"; {@code null} when resolvedId is null
 */
public record SearchProviderCatalogResponse(
        List<SearchProviderCatalogEntry> providers,
        String resolvedId,
        String resolvedSource
) {
}
```

- [ ] **Step 5: 改 `SystemSettingService`** — 加 import：

```java
import vip.mate.plugin.PluginManager;
import vip.mate.tool.search.SearchProvider;
import vip.mate.tool.search.SearchProviderRegistry;
```

字段区（`systemSettingMapper` 声明处）改为三个字段：

```java
    private final SystemSettingMapper systemSettingMapper;
    private final SearchProviderRegistry searchProviderRegistry;
    private final PluginManager pluginManager;
```

在类里加方法（放在 `getSearchSettings()` 附近，逻辑上属于同一组"搜索配置"方法）：

```java
    /**
     * 搜索 provider catalog：内置 + 插件注册的全部 provider，标注是否可用、
     * 属于哪个插件，以及当前实际会被 resolve() 选中的是哪一个（issue #477）。
     */
    public SearchProviderCatalogResponse getSearchProviderCatalog() {
        SystemSettingsDTO config = getSearchSettings();

        List<SearchProviderCatalogEntry> entries = searchProviderRegistry.allSorted().stream()
                .map(p -> toEntry(p, config))
                .toList();

        SearchProviderRegistry.ResolvedProvider resolved = searchProviderRegistry.resolve(config);
        String resolvedId = resolved != null ? resolved.provider().id() : null;
        String resolvedSource = resolved != null ? resolved.source() : null;

        return new SearchProviderCatalogResponse(entries, resolvedId, resolvedSource);
    }

    private SearchProviderCatalogEntry toEntry(SearchProvider provider, SystemSettingsDTO config) {
        boolean builtin = !searchProviderRegistry.isPluginProvider(provider.id());
        String pluginName = builtin ? null : pluginManager.getPluginNameForSearchProvider(provider.id());
        return new SearchProviderCatalogEntry(
                provider.id(),
                provider.label(),
                builtin,
                provider.requiresCredential(),
                provider.isAvailable(config),
                pluginName
        );
    }
```

（需要 `import vip.mate.system.model.SearchProviderCatalogEntry;` 和 `import vip.mate.system.model.SearchProviderCatalogResponse;` —— 若这两个类和 `SystemSettingService` 同包 `vip.mate.system.model` vs `vip.mate.system.service`，按实际包路径加 import；上面 Step 3/4 两个类放在 `vip.mate.system.model` 包，`SystemSettingService` 在 `vip.mate.system.service` 包，需要 import。还需 `import java.util.List;` 如果原文件未导入。）

- [ ] **Step 6: 同步改 `SystemSettingBoolApiTest`** — 构造点从 `new SystemSettingService(mapper)` 改为：

```java
        service = new SystemSettingService(mapper, new SearchProviderRegistry(java.util.List.of()), org.mockito.Mockito.mock(vip.mate.plugin.PluginManager.class));
```

（该测试类不测 catalog 逻辑，用真实空 registry + mock PluginManager 即可满足构造器，不用额外 import 到顶部——按现有文件风格决定是加顶部 import 还是用全限定名，参照文件里其他地方的习惯用顶部 import。）

- [ ] **Step 7: 跑测试确认通过**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test -Dtest='SystemSettingServiceCatalogTest,SystemSettingBoolApiTest'
```

预期：两个类全 PASS（4 + 5 = 9 个测试）。

- [ ] **Step 8: 跑 system 包全量测试确认无其他遗漏的构造点**

```bash
mvn test -Dtest='vip.mate.system.**'
```

若还有其他文件直接 `new SystemSettingService(...)`，按同样方式补齐参数。

- [ ] **Step 9: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-server/src/main/java/vip/mate/system/ \
        mateclaw-server/src/test/java/vip/mate/system/service/
git commit -m "feat(settings): add search provider catalog aggregation service (#477)"
```

---

### Task 4: `GET /api/v1/settings/search-providers` 接口

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/system/controller/SystemSettingController.java`

（纯薄 controller 委托，跟着现有 `getSettings()`/`saveSettings()` 的注解风格走，不需要新写 controller 单测——项目里 controller 层普遍没有专门的单测，业务逻辑的正确性已经在 Task 3 的 service 测试里覆盖了。）

- [ ] **Step 1: 读现有 `getSettings()` 方法的注解风格**，照抄加一个新端点（放在 `getSettings()`/`saveSettings()` 附近）：

```java
    @Operation(summary = "获取搜索 provider catalog（内置 + 插件），及当前实际生效的 provider")
    @GetMapping("/search-providers")
    @RequireWorkspaceRole("admin")
    public R<SearchProviderCatalogResponse> getSearchProviders() {
        return R.ok(systemSettingService.getSearchProviderCatalog());
    }
```

（`@Operation` 的 import、`R` 的 import、`@RequireWorkspaceRole` 的 import 均已在文件里存在，照抄现有端点的写法；新增 `import vip.mate.system.model.SearchProviderCatalogResponse;`。）

- [ ] **Step 2: 编译确认无误**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn -q compile
```

- [ ] **Step 3: 启动服务手工验证**（复用 PR-1 用过的登录流程）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
nohup mvn spring-boot:run > /tmp/mateclaw-pr2-verify.log 2>&1 &
```

等待启动完成（`grep -q "Started MateClawApplication" /tmp/mateclaw-pr2-verify.log` 变真），然后：

```bash
TOKEN=$(curl -s -X POST http://localhost:18088/api/v1/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])")
curl -s http://localhost:18088/api/v1/settings/search-providers -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

预期：返回 4 个内置 provider（serper/tavily/searxng/duckduckgo）+ `resolvedId`/`resolvedSource`（默认应为 `duckduckgo` / `keyless-fallback`，因为开发环境默认没配任何 API Key）。

验证完 `kill %1` 停掉服务。

- [ ] **Step 4: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-server/src/main/java/vip/mate/system/controller/SystemSettingController.java
git commit -m "feat(settings): expose GET /api/v1/settings/search-providers catalog endpoint (#477)"
```

---

### Task 5: 前端 API 客户端 + `useSearchProviderCatalog` 组合式函数（TDD，Vitest）

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`
- Modify: `mateclaw-ui/src/types/index.ts`
- Create: `mateclaw-ui/src/composables/useSearchProviderCatalog.ts`
- Create: `mateclaw-ui/src/composables/__tests__/useSearchProviderCatalog.test.ts`

**设计**：把"给下拉框生成选项列表"和"决定默认展开哪张卡片"这两段纯逻辑抽成可单测的组合式函数，Vue 组件本身（Task 6）只负责渲染，手工过浏览器验证。

- [ ] **Step 1: 加类型** — `mateclaw-ui/src/types/index.ts`，在 `SystemSettings` interface 附近加：

```typescript
export interface SearchProviderCatalogEntry {
  id: string
  label: string
  builtin: boolean
  requiresCredential: boolean
  available: boolean
  pluginName: string | null
}

export interface SearchProviderCatalog {
  providers: SearchProviderCatalogEntry[]
  resolvedId: string | null
  resolvedSource: string | null
}
```

- [ ] **Step 2: 加 API 客户端方法** — `mateclaw-ui/src/api/index.ts` 的 `settingsApi` 对象里加一行：

```typescript
  getSearchProviders: () => http.get('/settings/search-providers'),
```

- [ ] **Step 3: 写失败测试** — 新建 `mateclaw-ui/src/composables/__tests__/useSearchProviderCatalog.test.ts`：

```typescript
import { describe, expect, it } from 'vitest'
import { buildProviderOptions, resolveDefaultExpandedId } from '../useSearchProviderCatalog'
import type { SearchProviderCatalog } from '@/types'

const catalog: SearchProviderCatalog = {
  providers: [
    { id: 'serper', label: 'Serper (Google)', builtin: true, requiresCredential: true, available: false, pluginName: null },
    { id: 'duckduckgo', label: 'DuckDuckGo', builtin: true, requiresCredential: false, available: true, pluginName: null },
    { id: 'my-search', label: 'My Search', builtin: false, requiresCredential: true, available: true, pluginName: 'my-plugin' },
  ],
  resolvedId: 'duckduckgo',
  resolvedSource: 'keyless-fallback',
}

describe('buildProviderOptions', () => {
  it('prepends an auto option with empty-string value', () => {
    const options = buildProviderOptions(catalog, 'auto-label')
    expect(options[0]).toEqual({ value: '', label: 'auto-label' })
    expect(options).toHaveLength(4)
  })

  it('maps each catalog entry to a value/label pair preserving order', () => {
    const options = buildProviderOptions(catalog, 'auto-label')
    expect(options.slice(1)).toEqual([
      { value: 'serper', label: 'Serper (Google)' },
      { value: 'duckduckgo', label: 'DuckDuckGo' },
      { value: 'my-search', label: 'My Search' },
    ])
  })

  it('returns just the auto option when the catalog is empty', () => {
    const options = buildProviderOptions({ providers: [], resolvedId: null, resolvedSource: null }, 'auto-label')
    expect(options).toEqual([{ value: '', label: 'auto-label' }])
  })
})

describe('resolveDefaultExpandedId', () => {
  it('expands the resolved provider when present', () => {
    expect(resolveDefaultExpandedId(catalog)).toBe('duckduckgo')
  })

  it('falls back to the first provider when nothing is resolved', () => {
    const noneResolved = { ...catalog, resolvedId: null, resolvedSource: null }
    expect(resolveDefaultExpandedId(noneResolved)).toBe('serper')
  })

  it('returns null when the catalog has no providers at all', () => {
    expect(resolveDefaultExpandedId({ providers: [], resolvedId: null, resolvedSource: null })).toBeNull()
  })
})
```

- [ ] **Step 4: 跑测试确认失败**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-ui
pnpm test -- useSearchProviderCatalog
```

预期：找不到模块 `../useSearchProviderCatalog`。

- [ ] **Step 5: 实现** — 新建 `mateclaw-ui/src/composables/useSearchProviderCatalog.ts`：

```typescript
import type { SearchProviderCatalog } from '@/types'

export interface ProviderOption {
  value: string
  label: string
}

/** Turns a catalog into <select> options, with a synthetic "auto" option prepended (value ''). */
export function buildProviderOptions(catalog: SearchProviderCatalog, autoLabel: string): ProviderOption[] {
  const options: ProviderOption[] = [{ value: '', label: autoLabel }]
  for (const entry of catalog.providers) {
    options.push({ value: entry.id, label: entry.label })
  }
  return options
}

/** Which provider card should be expanded by default: the currently-resolved one, else the first. */
export function resolveDefaultExpandedId(catalog: SearchProviderCatalog): string | null {
  if (catalog.resolvedId) return catalog.resolvedId
  return catalog.providers.length > 0 ? catalog.providers[0].id : null
}
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-ui
pnpm test -- useSearchProviderCatalog
```

预期：6 个测试全 PASS。

- [ ] **Step 7: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-ui/src/api/index.ts mateclaw-ui/src/types/index.ts \
        mateclaw-ui/src/composables/useSearchProviderCatalog.ts \
        mateclaw-ui/src/composables/__tests__/useSearchProviderCatalog.test.ts
git commit -m "feat(settings-ui): add search provider catalog API client and selection logic (#477)"
```

---

### Task 6: 搜索设置页重构（`Settings/System/index.vue`）+ i18n

**Files:**
- Modify: `mateclaw-ui/src/views/Settings/System/index.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

这一步是纯前端渲染改动，没有可单测的分支逻辑（已在 Task 5 抽出去测过），验证方式是手工过浏览器（Task 8 统一做）。

- [ ] **Step 1: i18n 加新 key** — `zh-CN.ts`，在 `fields:` 块的 `searxngBaseUrl: 'SearXNG 地址',` 之后加：

```typescript
      searchProviderAuto: '自动选择（推荐）',
```

在 `hints:` 块对应位置加：

```typescript
      searchProviderAuto: '让系统按优先级自动挑选一个已配置好的 provider。',
```

在 `settings` 顶层对象（`searchTitle`/`searchDesc` 附近）加一组新 key（结构参照现有 `searchTitle` 的缩进层级）：

```typescript
    searchResolvedLabel: '当前实际生效',
    searchResolvedSource: {
      configured: '手动指定',
      autoDetect: '自动探测',
      keylessFallback: '免 Key 兜底',
    },
    searchStatusConfigured: '已配置',
    searchStatusNotConfigured: '未配置',
    searchStatusActive: '生效中',
    searchStatusNoCredential: '无需配置',
    searchPluginManaged: '该 Provider 由插件「{plugin}」提供，请在插件页配置',
    searchGoToPlugins: '前往插件页 →',
```

- [ ] **Step 2: en-US.ts 加对应英文 key**（结构、缩进与 zh-CN.ts 对齐，key 名完全一致）：

```typescript
      searchProviderAuto: 'Auto (recommended)',
```

```typescript
      searchProviderAuto: 'Let the system auto-pick the first configured provider by priority.',
```

```typescript
    searchResolvedLabel: 'Currently active',
    searchResolvedSource: {
      configured: 'manually configured',
      autoDetect: 'auto-detected',
      keylessFallback: 'keyless fallback',
    },
    searchStatusConfigured: 'Configured',
    searchStatusNotConfigured: 'Not configured',
    searchStatusActive: 'Active',
    searchStatusNoCredential: 'No credential needed',
    searchPluginManaged: 'Provided by plugin "{plugin}" — configure it on the Plugins page',
    searchGoToPlugins: 'Go to Plugins →',
```

- [ ] **Step 3: 改 `Settings/System/index.vue` 的 `<script setup>`** — 加 imports 和状态：

```typescript
import { onMounted, reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { buildProviderOptions, resolveDefaultExpandedId } from '@/composables/useSearchProviderCatalog'
import type { SearchProviderCatalog } from '@/types'
```

（`onMounted, reactive, ref` 已存在，只需补 `computed`；`useRouter` 用于"前往插件页"跳转链接。）

加状态和加载逻辑（`savedTip` 附近）：

```typescript
const router = useRouter()
const providerCatalog = ref<SearchProviderCatalog>({ providers: [], resolvedId: null, resolvedSource: null })
const expandedProviderId = ref<string | null>(null)

const providerOptions = computed(() => buildProviderOptions(providerCatalog.value, t('settings.fields.searchProviderAuto')))

function isExpanded(id: string) {
  return expandedProviderId.value === id
}
function toggleExpanded(id: string) {
  expandedProviderId.value = isExpanded(id) ? null : id
}
function goToPlugins() {
  router.push('/plugins')
}

async function loadProviderCatalog() {
  const res: any = await settingsApi.getSearchProviders()
  providerCatalog.value = res.data || { providers: [], resolvedId: null, resolvedSource: null }
  expandedProviderId.value = resolveDefaultExpandedId(providerCatalog.value)
}
```

在 `onMounted` 里追加调用：

```typescript
onMounted(async () => {
  await loadSettings()
  await loadProviderCatalog()
})
```

- [ ] **Step 4: 改模板** — 把现有"主 provider 下拉"替换成动态选项：

```html
<select v-model="settings.searchProvider" class="form-input" :disabled="!settings.searchEnabled">
  <option v-for="opt in providerOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
</select>
```

紧跟其后加"当前实际生效"状态行：

```html
<div v-if="providerCatalog.resolvedId" class="setting-item">
  <div class="setting-info">
    <div class="setting-hint">
      ✓ {{ t('settings.searchResolvedLabel') }}:
      {{ providerCatalog.providers.find(p => p.id === providerCatalog.resolvedId)?.label }}
      （{{ t('settings.searchResolvedSource.' + (providerCatalog.resolvedSource === 'configured' ? 'configured' : providerCatalog.resolvedSource === 'auto-detect' ? 'autoDetect' : 'keylessFallback')) }}）
    </div>
  </div>
</div>
```

把原来 9 个平铺的 `setting-item`（`searchFallbackEnabled` 之后的 Serper/Tavily/DuckDuckGo/SearXNG 全部字段）**移到**一个 `v-for` 折叠卡片结构里：

```html
<div v-for="entry in providerCatalog.providers" :key="entry.id" class="provider-card">
  <div class="provider-card-header" @click="toggleExpanded(entry.id)">
    <span class="provider-card-name">{{ entry.label }}</span>
    <span class="provider-card-badges">
      <span v-if="entry.id === providerCatalog.resolvedId" class="badge badge-active">{{ t('settings.searchStatusActive') }}</span>
      <span v-else-if="!entry.requiresCredential" class="badge">{{ t('settings.searchStatusNoCredential') }}</span>
      <span v-else-if="entry.available" class="badge badge-ok">{{ t('settings.searchStatusConfigured') }}</span>
      <span v-else class="badge badge-warn">{{ t('settings.searchStatusNotConfigured') }}</span>
    </span>
    <span class="provider-card-chevron">{{ isExpanded(entry.id) ? '▾' : '▸' }}</span>
  </div>

  <div v-if="isExpanded(entry.id)" class="provider-card-body">
    <!-- 插件 provider：不放表单，指路插件页 -->
    <p v-if="!entry.builtin" class="setting-hint">
      {{ t('settings.searchPluginManaged', { plugin: entry.pluginName }) }}
      <a href="#" @click.prevent="goToPlugins">{{ t('settings.searchGoToPlugins') }}</a>
    </p>

    <!-- 内置 provider：按 id 显示对应的现有字段组，逻辑和字段名完全不变 -->
    <template v-if="entry.id === 'serper'">
      <div class="setting-item setting-item-vertical">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.serperApiKey') }}</div>
          <div class="setting-hint">{{ t('settings.hints.serperApiKey') }}</div>
        </div>
        <div class="setting-control-full">
          <input
            v-model="serperApiKeyInput"
            type="password"
            class="form-input"
            :placeholder="settings.serperApiKeyMasked || t('settings.model.apiKeyInput')"
            :disabled="!settings.searchEnabled"
            autocomplete="off"
          />
        </div>
      </div>
      <div class="setting-item setting-item-vertical">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.serperBaseUrl') }}</div>
          <div class="setting-hint">{{ t('settings.hints.serperBaseUrl') }}</div>
        </div>
        <div class="setting-control-full">
          <input
            v-model="settings.serperBaseUrl"
            type="text"
            class="form-input"
            placeholder="https://google.serper.dev/search"
            :disabled="!settings.searchEnabled"
          />
        </div>
      </div>
    </template>
    <template v-else-if="entry.id === 'tavily'">
      <div class="setting-item setting-item-vertical">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.tavilyApiKey') }}</div>
          <div class="setting-hint">{{ t('settings.hints.tavilyApiKey') }}</div>
        </div>
        <div class="setting-control-full">
          <input
            v-model="tavilyApiKeyInput"
            type="password"
            class="form-input"
            :placeholder="settings.tavilyApiKeyMasked || t('settings.model.apiKeyInput')"
            :disabled="!settings.searchEnabled"
            autocomplete="off"
          />
        </div>
      </div>
      <div class="setting-item setting-item-vertical">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.tavilyBaseUrl') }}</div>
          <div class="setting-hint">{{ t('settings.hints.tavilyBaseUrl') }}</div>
        </div>
        <div class="setting-control-full">
          <input
            v-model="settings.tavilyBaseUrl"
            type="text"
            class="form-input"
            placeholder="https://api.tavily.com/search"
            :disabled="!settings.searchEnabled"
          />
        </div>
      </div>
    </template>
    <template v-else-if="entry.id === 'duckduckgo'">
      <div class="setting-item">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.duckduckgoEnabled') }}</div>
          <div class="setting-hint">{{ t('settings.hints.duckduckgoEnabled') }}</div>
        </div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input v-model="settings.duckduckgoEnabled" type="checkbox" :disabled="!settings.searchEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
      </div>
    </template>
    <template v-else-if="entry.id === 'searxng'">
      <div class="setting-item setting-item-vertical">
        <div class="setting-info">
          <div class="setting-label">{{ t('settings.fields.searxngBaseUrl') }}</div>
          <div class="setting-hint">{{ t('settings.hints.searxngBaseUrl') }}</div>
        </div>
        <div class="setting-control-full">
          <input
            v-model="settings.searxngBaseUrl"
            type="text"
            class="form-input"
            placeholder="http://searxng:8080"
            :disabled="!settings.searchEnabled"
          />
        </div>
      </div>
    </template>
  </div>
</div>
```

**删除**原文件里这四组字段现在所在位置的旧标记（上面这段已原样包含它们的完整内容，包括 `<!-- Serper 配置 -->`/`<!-- Tavily 配置 -->`/`<!-- Keyless Provider 配置 -->` 注释所标注的全部块），连同已被 catalog 动态渲染取代的旧硬编码 `<select>`（`<option value="serper">...</option>`/`<option value="tavily">...</option>` 两行）一起删掉，避免重复渲染。`onSaveSettings()`/`serperApiKeyInput`/`tavilyApiKeyInput`/`settings` reactive 对象定义**一行不改**。

- [ ] **Step 5: 补样式** — `<style scoped>` 里加折叠卡片相关 class（参照 `.settings-card`/`.setting-item` 现有变量令牌 `var(--mc-*)` 保持视觉一致）：

```css
.provider-card { border: 1px solid var(--mc-border); border-radius: 12px; margin-bottom: 12px; overflow: hidden; }
.provider-card-header { display: flex; align-items: center; gap: 10px; padding: 12px 16px; cursor: pointer; background: var(--mc-bg-elevated); }
.provider-card-name { font-weight: 600; flex: 1; }
.provider-card-badges { display: flex; gap: 6px; }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--mc-bg-sunken); color: var(--mc-text-secondary); }
.badge-active { background: var(--mc-primary); color: white; }
.badge-ok { background: rgba(34, 197, 94, 0.15); color: rgb(22, 163, 74); }
.badge-warn { background: rgba(234, 179, 8, 0.15); color: rgb(161, 98, 7); }
.provider-card-chevron { color: var(--mc-text-secondary); }
.provider-card-body { padding: 4px 16px 16px; }
```

- [ ] **Step 6: 前端类型检查**（不启动浏览器，先过编译）

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-ui
pnpm run build
```

预期：BUILD 成功，无 TS 报错。

- [ ] **Step 7: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-ui/src/views/Settings/System/index.vue \
        mateclaw-ui/src/i18n/locales/zh-CN.ts mateclaw-ui/src/i18n/locales/en-US.ts
git commit -m "feat(settings-ui): grouped collapsible provider cards driven by the catalog endpoint (#477)"
```

---

### Task 7: `Plugins.vue` — 展示 `registeredSearchProviders` + schema 驱动配置表单

**Files:**
- Modify: `mateclaw-ui/src/views/Plugins.vue`
- Modify: `mateclaw-ui/src/i18n/locales/zh-CN.ts`
- Modify: `mateclaw-ui/src/i18n/locales/en-US.ts`

- [ ] **Step 1: i18n 加新 key** — `zh-CN.ts` 的 `plugins:` 块（`memoryProvider` 附近）加：

```typescript
      searchProviders: '搜索提供商',
      configure: '配置',
      configTitle: '插件配置',
      configSave: '保存',
      configCancel: '取消',
      configSaved: '配置已保存',
      configRequired: '必填',
      configSecretPlaceholder: '（留空表示不修改）',
```

- [ ] **Step 2: en-US.ts 对应 key**：

```typescript
      searchProviders: 'Search providers',
      configure: 'Configure',
      configTitle: 'Plugin configuration',
      configSave: 'Save',
      configCancel: 'Cancel',
      configSaved: 'Configuration saved',
      configRequired: 'required',
      configSecretPlaceholder: '(leave blank to keep unchanged)',
```

- [ ] **Step 3: 展示 `registeredSearchProviders`** — 在 `plugin-capabilities` 区块（`registeredMemoryProvider` 那个 `capability-section` 之后）加：

```html
<div class="capability-section" v-if="plugin.registeredSearchProviders?.length">
  <span class="capability-label">{{ t('plugins.searchProviders') }}:</span>
  <span class="capability-tag" v-for="sp in plugin.registeredSearchProviders" :key="sp">{{ sp }}</span>
</div>
```

（`hasCapabilities(plugin)` 函数需要一并检查这个新字段，否则某插件只注册了 search provider、其他能力都为空时，`plugin-capabilities` 整块会被判定为"无能力"而不渲染——找到 `hasCapabilities` 函数定义，把 `plugin.registeredSearchProviders?.length` 加进判断条件的 `||` 链。）

- [ ] **Step 4: 加"配置"按钮** — 在 `plugin-header` 的 toggle-switch 附近，或 `plugin-details` 之后，加一个按钮（仅当 `plugin.configSchema` 非空对象时显示）：

```html
<button
  v-if="plugin.configSchema && Object.keys(plugin.configSchema).length > 0"
  class="btn-secondary btn-configure"
  @click="openConfigDialog(plugin)"
>
  {{ t('plugins.configure') }}
</button>
```

- [ ] **Step 5: 加配置弹窗**（简单的自制 modal，不引入新的 UI 库依赖，参照项目里其他弹窗的写法风格——若项目已有一个通用 Modal 组件，优先复用；执行者先 `grep -rl "class=\"modal" mateclaw-ui/src/views/` 或搜索 `<Teleport` 确认有没有现成弹窗模式可抄，没有就用最简单的固定定位 div）：

```html
<div v-if="configDialogPlugin" class="modal-overlay" @click.self="closeConfigDialog">
  <div class="modal-panel">
    <h3>{{ t('plugins.configTitle') }} — {{ configDialogPlugin.displayName || configDialogPlugin.name }}</h3>
    <div v-for="(field, key) in configDialogPlugin.configSchema" :key="key" class="config-field">
      <label>
        {{ key }}
        <span v-if="field.required" class="required-mark">*{{ t('plugins.configRequired') }}</span>
      </label>
      <p v-if="field.description" class="config-field-desc">{{ field.description }}</p>
      <input
        v-if="field.secret"
        type="password"
        v-model="configDraft[key]"
        :placeholder="t('plugins.configSecretPlaceholder')"
        autocomplete="off"
      />
      <input v-else type="text" v-model="configDraft[key]" />
    </div>
    <div class="modal-actions">
      <button class="btn-secondary" @click="closeConfigDialog">{{ t('plugins.configCancel') }}</button>
      <button class="btn-primary" @click="saveConfigDialog">{{ t('plugins.configSave') }}</button>
    </div>
  </div>
</div>
```

- [ ] **Step 6: `<script setup>` 加对应逻辑**：

```typescript
const configDialogPlugin = ref<any>(null)
const configDraft = ref<Record<string, string>>({})

function openConfigDialog(plugin: any) {
  configDialogPlugin.value = plugin
  const draft: Record<string, string> = {}
  for (const key of Object.keys(plugin.configSchema || {})) {
    // secret 字段永远从空白开始（不回显明文），非 secret 字段回显当前值
    draft[key] = plugin.configSchema[key].secret ? '' : (plugin.currentConfig?.[key] ?? '')
  }
  configDraft.value = draft
}

function closeConfigDialog() {
  configDialogPlugin.value = null
  configDraft.value = {}
}

async function saveConfigDialog() {
  if (!configDialogPlugin.value) return
  // 仅提交非空字段：secret 留空表示不修改；非 secret 字段允许提交空字符串覆盖
  const payload: Record<string, string> = {}
  for (const [key, value] of Object.entries(configDraft.value)) {
    const schema = configDialogPlugin.value.configSchema[key]
    if (schema.secret && !value) continue
    payload[key] = value
  }
  await pluginApi.updateConfig(configDialogPlugin.value.name, payload)
  mcToast.success(t('plugins.configSaved'))
  closeConfigDialog()
  await refresh()
}
```

（`refresh()` 函数应该已经存在于文件里，重新拉取插件列表刷新 `currentConfig`；`mcToast` 的 import 也应已存在。）

- [ ] **Step 7: 补样式**（`<style scoped>` 追加）：

```css
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-panel { background: var(--mc-bg-elevated); border-radius: 16px; padding: 24px; width: 480px; max-width: 90vw; max-height: 80vh; overflow-y: auto; }
.config-field { margin: 14px 0; }
.config-field label { display: block; font-weight: 600; margin-bottom: 4px; }
.config-field-desc { font-size: 13px; color: var(--mc-text-secondary); margin: 2px 0 6px; }
.config-field input { width: 100%; border: 1px solid var(--mc-border); border-radius: 8px; padding: 8px 10px; }
.required-mark { font-size: 12px; color: var(--mc-primary); font-weight: 400; margin-left: 6px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.btn-configure { margin-left: 8px; }
```

- [ ] **Step 8: 前端类型检查**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-ui
pnpm run build
```

- [ ] **Step 9: Commit**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git add mateclaw-ui/src/views/Plugins.vue \
        mateclaw-ui/src/i18n/locales/zh-CN.ts mateclaw-ui/src/i18n/locales/en-US.ts
git commit -m "feat(plugins-ui): show registered search providers and add a schema-driven config form (#477)"
```

---

### Task 8: 全量回归 + 手工浏览器验证

- [ ] **Step 1: 后端全量测试**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server
mvn test 2>&1 | tail -20
```

预期：全绿。若出现 wiki E2E 测试因本机 `data/mateclaw.mv.db` 陈旧而报 schema 相关错误，先 `rm -f data/mateclaw.mv.db data/mateclaw.trace.db` 再重跑（已知本机环境陷阱，非代码问题）。

- [ ] **Step 2: 前端单测**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-ui
pnpm test
```

- [ ] **Step 3: 前端构建**

```bash
pnpm run build
```

- [ ] **Step 4: 端到端手工验证** —— 启动后端 + 前端 dev server，浏览器实测：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server && nohup mvn spring-boot:run > /tmp/mateclaw-e2e.log 2>&1 &
cd /Users/connor/workspace/ai-lab/mateclaw/mateclaw-ui && nohup pnpm dev > /tmp/mateclaw-ui-e2e.log 2>&1 &
```

打开 `http://localhost:5173`，`admin`/`admin123` 登录，验证：
1. 设置页搜索区块：主下拉第一项是"自动选择（推荐）"；4 个内置 provider 变成折叠卡片，默认展开的是当前实际生效的那个（开发环境应是 DuckDuckGo）；点标题能展开/折叠；卡片上的徽标（生效中/已配置/未配置/无需配置）跟实际状态一致。
2. 把 `~/.mateclaw/plugins/` 放一个 PR-1 产出的 `mateclaw-plugin-search-sample-*.jar`，重启后端，确认搜索设置页下拉里出现"Demo Search”、对应卡片显示"该 Provider 由插件「mateclaw-plugin-search-demo」提供"+跳转链接。
3. 插件页：该插件卡片显示"搜索提供商: demo-search"标签；点"配置"弹出表单，`baseUrl`/`apiKey` 两个字段渲染正确（后者是密码框），填值保存后 `GET /api/v1/plugins` 返回的 `currentConfig` 确认已持久化，`apiKey` 类字段脱敏显示。
4. 保存搜索设置（选中某个 provider）后刷新页面，确认回显正确、API Key 输入框不回显明文。

验证完清理进程和插件 jar：

```bash
kill %1 %2 2>/dev/null
rm -f ~/.mateclaw/plugins/mateclaw-plugin-search-sample-*.jar
rm -f /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server/data/mateclaw.mv.db /Users/connor/workspace/ai-lab/mateclaw/mateclaw-server/data/mateclaw.trace.db
```

- [ ] **Step 5: 若发现问题，修复后回到对应 Task 补 commit；全部通过则进入 Task 9**

---

### Task 9: 推分支 + 开 PR

- [ ] **Step 1: 推到 origin**

```bash
cd /Users/connor/workspace/ai-lab/mateclaw
git push -u origin feat/plugin-search-provider-pr2
```

- [ ] **Step 2: 开 PR（base=dev，stacked 在 #479 之上，diff 暂含 PR-1 内容）**

```bash
gh pr create --repo mateaix/mateclaw --base dev --head ncw1992120:feat/plugin-search-provider-pr2 \
  --title "feat(plugin/settings): search provider catalog endpoint + grouped settings UI + plugin config form (#477)" \
  --body "$(cat <<'EOF'
PR-2 of #477，**stack 在 #479 之上**（未合并前本 PR 的 diff 会包含 #479 的全部改动，#479 合并后会自动收窄）。

## 改动

**后端**
- `SearchProviderRegistry.isPluginProvider(id)` — 区分内置/插件来源
- `PluginManager.getPluginNameForSearchProvider(id)` — 反查某 search provider 属于哪个插件
- `SystemSettingService.getSearchProviderCatalog()` + 两个新 DTO（`SearchProviderCatalogEntry`/`SearchProviderCatalogResponse`）— 聚合 registry + plugin manager
- 新增只读接口 `GET /api/v1/settings/search-providers`

**前端**
- `useSearchProviderCatalog` 组合式函数（下拉选项生成 + 默认展开逻辑，Vitest 覆盖）
- 搜索设置页：主 provider 下拉从硬编码 2 项改为 catalog 动态渲染 + "自动选择"选项；4 个 provider 从平铺列表改为分组折叠卡片；新增"当前实际生效"状态提示
- 插件页：展示 `registeredSearchProviders`；补上此前后端已就绪但前端从未接上的 schema 驱动插件配置表单（`configSchema`/`currentConfig`/`updateConfig`），服务所有插件类型，不只 search

## 测试
- 后端：`SearchProviderRegistryPluginTest`(+1)、`PluginManagerSearchLookupTest`(3)、`SystemSettingServiceCatalogTest`(4)
- 前端：`useSearchProviderCatalog.test.ts`(6)
- 手工端到端：设置页折叠卡片 + 插件 provider 显示与跳转 + 插件配置表单保存/回显/脱敏

无破坏性改动：`GET/PUT /api/v1/settings` 现有字段不动；`registerSearchProvider`/`isPluginProvider` 等均为新增。
EOF
)"
```

- [ ] **Step 3: PR 链接贴回 issue #477**

```bash
gh issue comment 477 --repo mateaix/mateclaw --body "PR-2 已提交：<刚创建的 PR 链接>"
```

---

## 范围外（本计划不做）

- JWKS/公钥分发类完全不相关的历史遗留项——不涉及。
- 插件配置表单目前是自制最小 modal；若上游已有通用弹窗组件库约定，评审时按反馈切换，不在本计划预判。
- `settings.searchProvider` 允许的合法值集合仍是自由字符串（不新增后端校验枚举）——沿用现状，catalog 只是"展示+选择辅助"，不改变 `resolve()` 的既有信任模型。
